package dev.assetpipeline.domain;

import java.util.List;

public interface MarketDataProvider {

    ProviderId id();

    /**
     * @throws TransientProviderException when a retry may succeed
     * @throws BusinessProviderException  when retrying cannot help
     */
    ProviderSnapshot fetch(List<String> symbols);
}
