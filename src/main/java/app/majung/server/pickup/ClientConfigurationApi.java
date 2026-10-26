package app.majung.server.pickup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ClientConfigurationApi {
    record Configuration(boolean developmentToolsEnabled, String kakaoMapJavaScriptKey) { }
    private final DeploymentPolicy deploymentPolicy;
    private final String kakaoMapJavaScriptKey;

    // The Kakao Maps JavaScript key is public by design and restricted to registered web domains.
    ClientConfigurationApi(DeploymentPolicy deploymentPolicy,
                           @Value("${majung.kakao-map.javascript-key:}") String kakaoMapJavaScriptKey) {
        this.deploymentPolicy = deploymentPolicy;
        this.kakaoMapJavaScriptKey = kakaoMapJavaScriptKey.isBlank() ? null : kakaoMapJavaScriptKey.strip();
    }

    @GetMapping(value = "/api/v1/config", produces = "application/json")
    Configuration configuration() {
        return new Configuration(deploymentPolicy.developmentToolsEnabled(), kakaoMapJavaScriptKey);
    }
}
