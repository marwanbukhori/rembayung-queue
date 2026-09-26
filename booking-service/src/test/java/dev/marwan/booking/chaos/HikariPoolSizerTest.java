package dev.marwan.booking.chaos;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

class HikariPoolSizerTest {

    /** Review I5: with minimum-idle at its default, a smaller maximum alone evicts nothing. */
    @Test
    void shrinkingThePoolEvictsTheConnectionsAlreadyOpen() {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariConfigMXBean config = mock(HikariConfigMXBean.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(ds.getHikariConfigMXBean()).thenReturn(config);
        when(ds.getHikariPoolMXBean()).thenReturn(pool);
        when(config.getMaximumPoolSize()).thenReturn(5);

        new HikariPoolSizer(ds).resize(1);

        verify(config).setMaximumPoolSize(1);
        verify(config).setMinimumIdle(1);
        verify(pool).softEvictConnections();
    }

    @Test
    void growingThePoolBackEvictsNothing() {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariConfigMXBean config = mock(HikariConfigMXBean.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(ds.getHikariConfigMXBean()).thenReturn(config);
        when(ds.getHikariPoolMXBean()).thenReturn(pool);
        when(config.getMaximumPoolSize()).thenReturn(1);

        new HikariPoolSizer(ds).resize(5);

        verify(config).setMaximumPoolSize(5);
        verify(config).setMinimumIdle(5);
        verify(pool, never()).softEvictConnections();
    }
}
