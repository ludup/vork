package sh.vork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"sh.vork", "com.jadaptive.orm.mongo"})
public class VorkApplication {
    public static void main(String[] args) {
        SpringApplication.run(VorkApplication.class, args);
    }
}
