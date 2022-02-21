package cz.edu.upce.fei.datacollector.service.impl;

import cz.edu.upce.fei.datacollector.model.SensorData;
import cz.edu.upce.fei.datacollector.repository.DataRepository;
import cz.edu.upce.fei.datacollector.service.DataHandlerService;
import cz.edu.upce.fei.datacollector.service.SensorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataHandlerServiceImpl implements DataHandlerService {

    private final DataRepository dataRepository;
    private final SensorService sensorService;
    private final Collection<String> dataBuffer = Collections.synchronizedCollection(new ArrayList<>());

    @Override
    @Scheduled(cron = "${dataProcessingTask}")
    public void handleData() {
        List<SensorData> processedData = processData();

        // create new sensor if not existing;
        sensorService.validateSensorsExistOrCreate(processedData);

        dataRepository.saveData(processedData);
    }

    @Override
    public void addData(byte[] rawMessage) {
        String message = new String(rawMessage);
        log.debug("Data received: {}", message);
        dataBuffer.add(message);
    }

    // TODO change to private
    public List<SensorData> processData() {
        log.info("Start of data processing.");

        Map<Long, List<SensorData>> sensorDataMap = new HashMap<>();

        normalizeData(sensorDataMap);

        List<SensorData> resultData = sumUpData(sensorDataMap);

        //TODO change this behaviour
        log.info("Result of data processing:");
        for (SensorData sensorData : resultData) {
            log.info("{}", sensorData);
        }
        log.info("Data processing ended.");
        return resultData;
    }

    private List<SensorData> sumUpData(Map<Long, List<SensorData>> sensorDataMap) {
        log.debug("Start of data summarising");

        List<SensorData> resultData = new ArrayList<>();
        sensorDataMap.forEach((key, value) -> {

            // counting average on each field
            OptionalDouble avgTemper1 = value.stream().filter(it -> it.getTemperature1() != null)
                    .mapToDouble(SensorData::getTemperature1).average();
            OptionalDouble avgHumidity = value.stream().filter(it -> it.getHumidity() != null)
                    .mapToDouble(SensorData::getHumidity).average();
            OptionalDouble avgCo2_1 = value.stream().filter(it -> it.getCo2_1() != null)
                    .mapToInt(SensorData::getCo2_1).average();
            OptionalDouble avgCo2_2 = value.stream().filter(it -> it.getCo2_2() != null)
                    .mapToInt(SensorData::getCo2_2).average();
            OptionalDouble avgTemper2 = value.stream().filter(it -> it.getTemperature2() != null)
                    .mapToInt(SensorData::getTemperature2).average();

            // creating summarized Data entity for each message
            resultData.add(new SensorData(key, Timestamp.valueOf(LocalDateTime.now()), value.size(),
                    avgTemper1.isPresent() ? avgTemper1.getAsDouble() : null,
                    avgHumidity.isPresent() ? avgHumidity.getAsDouble() : null,
                    avgCo2_1.isPresent() ? (int) avgCo2_1.getAsDouble() : null,
                    avgCo2_2.isPresent() ? (int) avgCo2_2.getAsDouble() : null,
                    avgTemper2.isPresent() ? (int) avgTemper2.getAsDouble() : null
            ));
        });
        log.debug("Data summarising ended.");
        return resultData;
    }

    private void normalizeData(Map<Long, List<SensorData>> sensorDataMap) {
        log.debug("Start of data normalising");
        for (String message : dataBuffer) {
            // removing zero byte (end of byte array from c++)
            message = message.replace("\0", "");

            if (validateMessageFormat(message)) continue;

            String[] strings = message.split(";", 7);
            long sensorId = Long.parseLong(strings[0]);

            // separating data by sensor ID
            List<SensorData> sensorDataList = sensorDataMap.computeIfAbsent(sensorId, k -> new ArrayList<>());
            sensorDataList.add(transferRawMessageToData(strings, sensorId));
        }
        dataBuffer.clear();
        log.debug("Data normalised.");
    }

    private SensorData transferRawMessageToData(String[] strings, long sensorId) {
        // hits and timestamp is unused
        return new SensorData(sensorId, null, 0,
                strings[1].isEmpty() ? null : Double.parseDouble(strings[1]),
                strings[2].isEmpty() ? null : Double.parseDouble(strings[2]),
                strings[3].isEmpty() ? null : Integer.parseInt(strings[3]),
                strings[4].isEmpty() ? null : Integer.parseInt(strings[4]),
                strings[5].isEmpty() ? null : Integer.parseInt(strings[5]));
    }

    private boolean validateMessageFormat(String message) {
        if (!message.matches("^\\d+;((-?\\d+\\.\\d{0,2})|);((\\d+\\.\\d{0,2})|);((\\d{1,4})|);((\\d{1,4})|);((-?\\d+)|);$")) {
            log.warn("Message doesn't matches expected format: {}", message);
            return true;
        }
        return false;
    }
}
