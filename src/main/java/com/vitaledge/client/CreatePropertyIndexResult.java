package com.vitaledge.client;

public record CreatePropertyIndexResult(
    boolean created,
    long indexedEntities) {}