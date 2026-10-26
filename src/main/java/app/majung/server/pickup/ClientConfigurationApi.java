package app.majung.server.pickup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ClientConfigurationApi {
    record Configuration(boolean developmentToolsEnabled, String kakaoMapJavaScriptKey, String webPushPublicKey) { }
    private final DeploymentPolicy deploymentPolicy;
    private final String kakaoMapJavaScriptKey;
    private final ArrivalPushService arrivalPush;

    // The Kakao Maps JavaScript key is public by design and restricted to registered web domains.
    ClientConfigurationApi(DeploymentPolicy deploymentPolicy,
                           ArrivalPushService arrivalPush,
                           @Value("${majung.kakao-map.javascript-key:}") String kakaoMapJavaScriptKey) {
        this.arrivalPush = arrivalPush;
        this.deploymentPolicy = deploymentPolicy;
        this.kakaoMapJavaScriptKey = kakaoMapJavaScriptKey.isBlank() ? null : kakaoMapJavaScriptKey.strip();
    }

    @GetMapping(value = "/api/v1/config", produces = "application/json")
    Configuration configuration() {
        return new Configuration(deploymentPolicy.developmentToolsEnabled(), kakaoMapJavaScriptKey,
                arrivalPush.enabled() ? arrivalPush.publicKey() : null);
    }
}
