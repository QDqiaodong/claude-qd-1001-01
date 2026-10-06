package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.PulpChest;

public interface PulpChestRepository extends JpaRepository<PulpChest, Long> {

    Optional<PulpChest> findByChestCode(String chestCode);

    List<PulpChest> findByStatus(String status);

    List<PulpChest> findByPulpType(String pulpType);

    /** 按浆种与状态筛，列表页用 */
    List<PulpChest> findByPulpTypeAndStatus(String pulpType, String status);
}
