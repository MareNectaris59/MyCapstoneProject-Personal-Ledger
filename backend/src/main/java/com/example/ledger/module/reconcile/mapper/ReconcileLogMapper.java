package com.example.ledger.module.reconcile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.module.reconcile.entity.ReconcileLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReconcileLogMapper extends BaseMapper<ReconcileLog> {
}
