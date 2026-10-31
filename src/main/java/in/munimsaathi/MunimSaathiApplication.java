package in.munimsaathi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MunimSaathiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MunimSaathiApplication.class, args);
    }
}
