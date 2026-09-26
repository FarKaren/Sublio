package subtitle.configs

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties (
    val processedFileDir: String
)