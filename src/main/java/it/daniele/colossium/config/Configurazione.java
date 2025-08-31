package it.daniele.colossium.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import secrets.ConstantColossium;

@Configuration
public class Configurazione {
    @Bean(value = "tokenBot")
    public String tokenBot(){
        return ConstantColossium.BOT_TOKEN;
    }

}
