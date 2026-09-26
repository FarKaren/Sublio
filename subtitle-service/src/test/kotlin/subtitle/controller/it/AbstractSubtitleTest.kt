package subtitle.controller.it

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.nio.file.Files
import java.nio.file.Path


@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractSubtitleTest {

    companion object {
        val NETWORK: Network? = Network.newNetwork()


        @ServiceConnection
        val springContainer = PostgreSQLContainer("postgres:latest")
            .withNetwork(NETWORK)
            .withDatabaseName("sublio")
            .withUsername("postgres")
            .withPassword("postgres")
            .withExposedPorts(5432)
            .withInitScript("db/init.sql")
            .withNetworkAliases("postgres")
            .waitingFor(Wait.forListeningPort())

        init {
            springContainer.start()
        }


        lateinit var processedFileDir: Path

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            processedFileDir = Files.createTempDirectory("sublio-processed-it")
            registry.add("app.processed-file-dir") { processedFileDir.toString() }
        }
    }
}
