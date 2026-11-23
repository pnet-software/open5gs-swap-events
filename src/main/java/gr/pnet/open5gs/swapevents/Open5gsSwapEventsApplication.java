package gr.pnet.open5gs.swapevents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Open5gsSwapEventsApplication {

	public static void main(String[] args) {
		SpringApplication.run(Open5gsSwapEventsApplication.class, args);
	}

}
