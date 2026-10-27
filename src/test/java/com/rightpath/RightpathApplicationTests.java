package com.rightpath;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * The whole application context starts.
 *
 * <p>Cheap but broad: it is the only test that wires every bean together, so it
 * catches a bean definition that no longer resolves — a missing dependency, a
 * duplicate, an ambiguous constructor — which unit tests with mocked
 * collaborators cannot see.</p>
 *
 * <h2>Why the AWS properties are set here</h2>
 *
 * <p>{@code S3StorageService} builds its client in its constructor, and the AWS
 * SDK refuses blank credentials outright. The dev config supplies them from the
 * environment with an empty default ({@code ${AWS_ACCESS_KEY:}}), so on any
 * machine without those variables exported — CI included — the context failed
 * to start and this test reported a bean wiring error that was really just
 * absent credentials.</p>
 *
 * <p>These values are placeholders to get the client constructed; nothing here
 * reaches S3. Building the client makes no network call, and no test in this
 * class uploads or downloads anything. Setting them in the test rather than
 * relaxing the service keeps production behaviour unchanged: an environment
 * that is genuinely missing its credentials should still fail loudly at
 * startup, not lazily on a candidate's first upload.</p>
 *
 * <h2>Why a real servlet container</h2>
 *
 * <p>The default mock web environment has no {@code ServerContainer}, and
 * {@code WebSocketConfig} declares a {@link
 * org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean}
 * that asserts one is present. Booting Tomcat on a random port is what makes
 * the STOMP endpoint part of what this test covers — worth having, since the
 * interview socket is the one piece of wiring whose breakage reaches a
 * candidate as "Connection lost" rather than as a stack trace.</p>
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "aws.s3.access-key=test-access-key",
                "aws.s3.secret-key=test-secret-key",
        })
class RightpathApplicationTests {

	@Test
	void contextLoads() {
	}

}
