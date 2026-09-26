package dev.marwan.booking.chaos;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Resizes the live Hikari pool. A smaller maximum alone closes nothing: the
 * connections already open stay in use, so a squeeze also sets minimum-idle to
 * match and soft-evicts, which retires idle connections now and busy ones as
 * they are returned.
 */
final class HikariPoolSizer implements ChaosState.PoolSizer {

    private final HikariDataSource hikari;

    HikariPoolSizer(HikariDataSource hikari) {
        this.hikari = hikari;
    }

    @Override
    public int size() {
        return hikari.getHikariConfigMXBean().getMaximumPoolSize();
    }

    @Override
    public void resize(int n) {
        var config = hikari.getHikariConfigMXBean();
        boolean shrinking = n < config.getMaximumPoolSize();
        config.setMaximumPoolSize(n);
        config.setMinimumIdle(n);
        if (shrinking) {
            hikari.getHikariPoolMXBean().softEvictConnections();
        }
    }
}
