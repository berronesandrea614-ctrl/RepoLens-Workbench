package com.repolens.domain.vo;

import lombok.Data;

@Data
public class TodoItemVO {
    private String id;
    private String content;
    private String status;      // pending | in_progress | completed
    private String activeForm;  // in_progress 时的进行中形式
}
