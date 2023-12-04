package com.hmdp.entity;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    //用来存储需要分页显示的内容，数据元素用泛型存储，以便未来实现除笔记外的其他内容
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
