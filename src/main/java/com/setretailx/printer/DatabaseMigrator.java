package com.setretailx.printer;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.ApiExchangeLogRepository;
import com.setretailx.printer.repository.ChangedLabelPrintHistoryRepository;
import com.setretailx.printer.repository.PrintHistoryRepository;
import com.setretailx.printer.repository.PriceChangePrintHistoryRepository;
import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.service.PriceMonitoringTaskService;
import com.setretailx.printer.service.SetRetailXConnector;

import java.sql.SQLException;

/** Applies every local SQLite schema upgrade before any application window is created. */
public final class DatabaseMigrator {
    private DatabaseMigrator() { }

    public static void migrateAll() throws SQLException {
        new AddressStorageRepository();
        new ApiExchangeLogRepository().migrate();
        new SalesPointRepository();
        new PrintHistoryRepository().migrate();
        new PriceChangePrintHistoryRepository().migrate();
        new ChangedLabelPrintHistoryRepository().migrate();
        new PriceMonitoringTaskService(SetRetailXConnector.getInstance(), AppConfig.tasksDatabasePath());
    }
}
