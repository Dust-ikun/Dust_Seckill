package com.dustikun.seckill.Bench;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 压测影子表的两条扣减语句——本组实验的全部变量就在这一行 SQL 上。
 * <p>
 * cond：UPDATE ... WHERE id=? AND count>=?　（条件 UPDATE，冲突由 InnoDB 行锁排队消化）
 * opt ：UPDATE ... WHERE id=? AND version=?（乐观锁 CAS，冲突方影响行数 0，由应用层重试）
 */
@Mapper
public interface BenchStockMapper {

    @Select("SELECT id, `count`, version FROM bench_stock WHERE id = #{id}")
    BenchStock selectById(@Param("id") Long id);

    @Update("UPDATE bench_stock SET `count` = `count` - #{num} WHERE id = #{id} AND `count` >= #{num}")
    int deductCond(@Param("num") Long num, @Param("id") Long id);

    @Update("UPDATE bench_stock SET `count` = `count` - #{num}, version = version + 1 "
            + "WHERE id = #{id} AND version = #{version} AND `count` >= #{num}")
    int deductOpt(@Param("num") Long num, @Param("id") Long id, @Param("version") Long version);

    @Insert("INSERT INTO bench_stock (id, `count`, version) VALUES (#{id}, #{count}, 0) "
            + "ON DUPLICATE KEY UPDATE `count` = #{count}, version = 0")
    int upsert(@Param("id") Long id, @Param("count") Long count);
}
