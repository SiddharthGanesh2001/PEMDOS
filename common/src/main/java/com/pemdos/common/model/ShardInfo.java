package com.pemdos.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ShardInfo {
    private int shardIndex;
    private byte[] data;
    private String sha256Hash;
    private boolean isParity;
}
