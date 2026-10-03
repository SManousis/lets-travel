package com.travelplan.travel;
import org.neo4j.driver.*; import org.springframework.context.annotation.*; import org.springframework.context.annotation.Profile; import org.springframework.beans.factory.annotation.Value;
@Configuration @Profile("infra") public class Neo4jConfig {
 @Bean(destroyMethod="close") Driver neo4jDriver(@Value("${neo4j.uri:bolt://neo4j:7687}") String uri,@Value("${neo4j.username:neo4j}") String user,@Value("${neo4j.password:change-me-neo4j}") String password){return GraphDatabase.driver(uri,AuthTokens.basic(user,password));}
}
