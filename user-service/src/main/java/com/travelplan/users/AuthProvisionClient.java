package com.travelplan.users;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Component; import org.springframework.web.client.RestClient;
import java.util.UUID;
@Component public class AuthProvisionClient {
 private final RestClient client; private final String token;
 public AuthProvisionClient(@Value("${auth.service-url:http://auth-service:8081}") String url,@Value("${security.internal-service-token:change-me-internal}") String token){this.client=RestClient.builder().baseUrl(url).build();this.token=token;}
 public void provision(UUID userId,String username,String password,String role){client.post().uri("/api/auth/internal/provision").header("X-Internal-Service-Token",token).body(new Request(userId,username,password,role)).retrieve().toBodilessEntity();}
 public void update(UUID userId,String username,String role,boolean enabled){client.post().uri("/api/auth/internal/identities/{id}",userId).header("X-Internal-Service-Token",token).body(new UpdateRequest(username,role,enabled)).retrieve().toBodilessEntity();}
 record Request(UUID userId,String username,String password,String role){}
 record UpdateRequest(String username,String role,boolean enabled){}
}
