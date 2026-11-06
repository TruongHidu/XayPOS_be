package com.possaas.modules.report.dto;

public record ReportEnvelope<T>(ReportMeta meta, T data) {}
