package com.auravision;

import com.auravision.model.Generation;
import com.auravision.repo.GenerationRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.List;

@SpringBootApplication
@EnableAsync
public class AuraVisionApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuraVisionApplication.class, args);
    }

    /** Jobs that were running when the server stopped can never finish, so mark them failed. */
    @Bean
    CommandLineRunner failInterruptedJobs(GenerationRepository repo) {
        return args -> repo.findByStatusIn(List.of(Generation.Status.PENDING, Generation.Status.RUNNING)).forEach(g -> {
            g.setStatus(Generation.Status.FAILED);
            g.setError("Interrupted by a server restart. Please generate again.");
            repo.save(g);
        });
    }
}
