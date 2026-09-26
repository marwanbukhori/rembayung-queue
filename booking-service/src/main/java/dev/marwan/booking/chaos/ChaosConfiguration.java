package dev.marwan.booking.chaos;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

/** The chaos state, wired to the real Hikari pool. */
@Configuration
public class ChaosConfiguration {

    @Bean
    ChaosState chaosState(DataSource dataSource) {
        ChaosState.PoolSizer sizer;
        if (dataSource instanceof HikariDataSource hikari) {
            sizer = new HikariPoolSizer(hikari);
        } else {
            sizer = new ChaosState.PoolSizer() {
                @Override public int size() { return 0; }
                @Override public void resize(int n) { }
            };
        }
        return new ChaosState(sizer, Clock.systemUTC());
    }
}
