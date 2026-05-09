package com.latencylab.reporting;

import com.latencylab.model.MetricsSnapshot;

public interface ReportingEngine {
    void printConsole(MetricsSnapshot snapshot);
    void writeCsv(MetricsSnapshot snapshot, String outputPath);
    void writeJson(MetricsSnapshot snapshot, String outputPath);
}
