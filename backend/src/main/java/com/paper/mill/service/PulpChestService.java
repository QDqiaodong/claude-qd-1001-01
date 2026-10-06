package com.paper.mill.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.entity.PulpChest;
import com.paper.mill.repository.PulpChestRepository;

/**
 * 制浆工段 · 浆池业务。
 *
 * <p>核心规则：① 只有「在用」的浆池能补浆；② 单次补浆量不得超过
 * {@link Dict#MAX_FILL_M3}；③ 补浆后存量不得超过容量。</p>
 */
@Service
public class PulpChestService {

    private final PulpChestRepository chestRepository;

    public PulpChestService(PulpChestRepository chestRepository) {
        this.chestRepository = chestRepository;
    }

    public List<PulpChest> list(String pulpType, String status) {
        if (pulpType != null && !pulpType.isBlank() && status != null && !status.isBlank()) {
            return chestRepository.findByPulpTypeAndStatus(pulpType, status);
        }
        if (pulpType != null && !pulpType.isBlank()) {
            return chestRepository.findByPulpType(pulpType);
        }
        if (status != null && !status.isBlank()) {
            return chestRepository.findByStatus(status);
        }
        return chestRepository.findAll();
    }

    public PulpChest get(Long id) {
        return chestRepository.findById(id)
                .orElseThrow(() -> new BizException("浆池不存在：" + id));
    }

    @Transactional
    public PulpChest create(PulpChest input) {
        if (input.getChestCode() == null || input.getChestCode().isBlank()) {
            throw new BizException("浆池编号不能为空");
        }
        chestRepository.findByChestCode(input.getChestCode().trim()).ifPresent(c -> {
            throw new BizException("浆池编号已存在：" + input.getChestCode());
        });
        if (!Dict.isValidPulpType(input.getPulpType())) {
            throw new BizException("浆种不合法：" + input.getPulpType());
        }
        if (input.getCapacityM3() == null || input.getCapacityM3().signum() <= 0) {
            throw new BizException("容量必须大于 0");
        }
        PulpChest chest = new PulpChest();
        chest.setChestCode(input.getChestCode().trim());
        chest.setChestName(input.getChestName() == null ? input.getChestCode() : input.getChestName());
        chest.setPulpType(input.getPulpType());
        chest.setCapacityM3(input.getCapacityM3());
        // 新池默认从空池起算
        chest.setCurrentM3(input.getCurrentM3() == null ? BigDecimal.ZERO : input.getCurrentM3());
        if (chest.getCurrentM3().compareTo(chest.getCapacityM3()) > 0) {
            throw new BizException("初始存量不能超过容量");
        }
        chest.setStatus(input.getStatus() == null || input.getStatus().isBlank()
                ? "在用" : input.getStatus());
        if (!Dict.isValidChestStatus(chest.getStatus())) {
            throw new BizException("浆池状态不合法：" + chest.getStatus());
        }
        chest.setRemark(input.getRemark());
        return chestRepository.save(chest);
    }

    @Transactional
    public PulpChest update(Long id, PulpChest input) {
        PulpChest chest = get(id);
        if (input.getChestName() != null && !input.getChestName().isBlank()) {
            chest.setChestName(input.getChestName());
        }
        if (input.getPulpType() != null && !input.getPulpType().isBlank()) {
            if (!Dict.isValidPulpType(input.getPulpType())) {
                throw new BizException("浆种不合法：" + input.getPulpType());
            }
            chest.setPulpType(input.getPulpType());
        }
        if (input.getCapacityM3() != null) {
            if (input.getCapacityM3().signum() <= 0) {
                throw new BizException("容量必须大于 0");
            }
            if (chest.getCurrentM3() != null && chest.getCurrentM3().compareTo(input.getCapacityM3()) > 0) {
                throw new BizException("容量不能小于当前存量（当前 "
                        + chest.getCurrentM3() + " 立方米）");
            }
            chest.setCapacityM3(input.getCapacityM3());
        }
        if (input.getRemark() != null) {
            chest.setRemark(input.getRemark());
        }
        return chestRepository.save(chest);
    }

    /**
     * 补浆。
     *
     * @param id     浆池
     * @param amount 补浆量（立方米）
     */
    @Transactional
    public PulpChest fill(Long id, BigDecimal amount) {
        PulpChest chest = get(id);
        if (!"在用".equals(chest.getStatus())) {
            throw new BizException("只有「在用」的浆池可以补浆，当前状态：" + chest.getStatus());
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BizException("补浆量必须大于 0");
        }
        if (amount.compareTo(Dict.MAX_FILL_M3) > 0) {
            throw new BizException("单次补浆量不得超过 " + Dict.MAX_FILL_M3 + " 立方米");
        }
        BigDecimal after = chest.getCurrentM3().add(amount);
        if (after.compareTo(chest.getCapacityM3()) > 0) {
            throw new BizException("补浆后存量 " + after + " 超过容量 "
                    + chest.getCapacityM3() + " 立方米，最多还能补 " + chest.getFreeM3());
        }
        chest.setCurrentM3(after);
        return chestRepository.save(chest);
    }

    /** 抽浆：按配比从浆池取浆 */
    @Transactional
    public PulpChest draw(Long id, BigDecimal amount) {
        PulpChest chest = get(id);
        if (!"在用".equals(chest.getStatus())) {
            throw new BizException("只有「在用」的浆池可以抽浆，当前状态：" + chest.getStatus());
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BizException("抽浆量必须大于 0");
        }
        if (amount.compareTo(chest.getCurrentM3()) > 0) {
            throw new BizException("存量不足，当前仅剩 " + chest.getCurrentM3() + " 立方米");
        }
        chest.setCurrentM3(chest.getCurrentM3().subtract(amount));
        return chestRepository.save(chest);
    }

    /** 状态流转：在用 → 检修 → 在用；停用池只能先转检修 */
    @Transactional
    public PulpChest transfer(Long id, String target) {
        PulpChest chest = get(id);
        if (!Dict.isValidChestStatus(target)) {
            throw new BizException("浆池状态不合法：" + target);
        }
        if (!Dict.canTransferChest(chest.getStatus(), target)) {
            throw new BizException("不允许从「" + chest.getStatus() + "」转为「" + target + "」");
        }
        chest.setStatus(target);
        return chestRepository.save(chest);
    }
}
