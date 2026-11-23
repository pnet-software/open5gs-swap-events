package gr.pnet.open5gs.swapevents.models;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "swap_events")
@AllArgsConstructor
public class Open5gsSwapCheckDocument {

    @Id
    private String msisdn; 

    private String imsi;
    private String imeisv;

    private LocalDateTime lastUpdatedImsi;
    private LocalDateTime lastUpdatedImeisv;
}
