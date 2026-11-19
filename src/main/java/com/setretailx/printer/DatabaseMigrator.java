package com.setretailx.printer;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.AccountRepository;
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
        if (AppConfig.nodeRole() == AppConfig.NodeRole.PARENT) {
            AccountRepository accounts = new AccountRepository();
            accounts.migrate();
            if (accounts.countAccounts() == 0) {
                System.err.println("Родительское хранилище аккаунтов пока пусто. До выдачи доступа продажам "
                        + "нужно создать административную учётную запись локальным мастером настройки.");
            }
        }
        new AddressStorageRepository();
        new ApiExchangeLogRepository().migrate();
        new SalesPointRepository();
        new PrintHistoryRepository().migrate();
        new PriceChangePrintHistoryRepository().migrate();
        new ChangedLabelPrintHistoryRepository().migrate();
        SetRetailXConnector connector = AppConfig.nodeRole() == AppConfig.NodeRole.PARENT
                ? SetRetailXConnector.getInstance() : SetRetailXConnector.unavailableOnChild();
        new PriceMonitoringTaskService(connector, AppConfig.tasksDatabasePath());
    }
}
