package dev.assetpipeline.config;

import dev.assetpipeline.application.ConsolidateResults;
import dev.assetpipeline.domain.ResultStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free use cases as Spring beans. */
@Configuration
public class UseCaseConfig {

    @Bean
    ConsolidateResults consolidateResults(ResultStore store) {
        return new ConsolidateResults(store);
    }
}
