package com.example.chat.airport;

import com.example.chat.airport.entity.Departure;
import com.example.chat.airport.entity.Plane;
import com.example.chat.airport.repository.DepartureRepository;
import com.example.chat.airport.repository.PlaneRepository;
import com.example.chat.airport.reqDto.DepartureDto;
import com.example.chat.airport.reqDto.PlaneDto;
import com.example.chat.airport.resDto.DepartureResDto;
import com.example.chat.airport.resDto.PlaneResDto;
import com.example.chat.exception.ChatException;
import com.example.chat.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
public class AirService {
    @Value("${data.api.key}") // 공공 데이터 API 키
    private String API_KEY;
    private final RestTemplate restTemplate = new RestTemplate();
    private final DepartureRepository departureRepository;
    private final PlaneRepository planeRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisTemplate<String, Object> redisTemplate;

    @Transactional
    public void getArrivalsData() {

        departureRepository.deleteAll();

        List<String> selectdates = new ArrayList<>();
        selectdates.add("0"); // 오늘 , Api에서 정한 규칙임 "0"은 오늘 / "1"은 내일 / "2"는 이틀 뒤 ...
        selectdates.add("1"); // 내일

        String endPoint = "http://apis.data.go.kr/B551177/PassengerNoticeKR/getfPassengerNoticeIKR";

        try {

            for (String selectdate : selectdates) {

                String url = endPoint + "?"
                        + "serviceKey=" + API_KEY
                        + "&selectdate=" + URLEncoder.encode(selectdate, StandardCharsets.UTF_8)
                        + "&numOfRows=" + URLEncoder.encode("9999", StandardCharsets.UTF_8)
                        + "&type=" + URLEncoder.encode("json", StandardCharsets.UTF_8);

                // restTemplate으로 보낼 때 인코딩 하고 보내야 된다네요
                URI uri = new URI(url);

                ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);

                saveArrivalData(response.getBody(), selectdate);
            }

        } catch (ChatException e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.ERROR_TO_SAVE_ARRIVAL_DATA);
        } catch (Exception e) {
            log.error("출국장 데이터 저장 예외 발생: {}", e.getMessage(), e);
        }
    }

    private void saveArrivalData(String jsonData, String selectdate) {
        try {

            if (jsonData == null || jsonData.isEmpty() || jsonData.startsWith("<")) {
                throw new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.ERROR_TO_CHANGE_JSON_DATE);
            }

            JsonNode root = objectMapper.readTree(jsonData);

            // 어느 특정 날짜의 데이터들
            JsonNode items = root.path("response").path("body").path("items");

            List<Departure> departures = new ArrayList<>();

            for (JsonNode item : items) {

                DepartureDto dto = DepartureDto.builder()
                        .date(item.path("adate").asText())
                        .timeZone(item.path("atime").asText())
                        .t1Depart12(item.path("t1sum5").asLong())
                        .t1Depart3(item.path("t1sum6").asLong())
                        .t1Depart4(item.path("t1sum7").asLong())
                        .t1Depart56(item.path("t1sum8").asLong())
                        .t1DepartSum(item.path("t1sumset2").asLong())
                        .t2Depart1(item.path("t2sum3").asLong())
                        .t2Depart2(item.path("t2sum4").asLong())
                        .t2DepartSum(item.path("t2sumset2").asLong())
                        .build();

                if (item.path("adate").asText().equals("합계")) {
                    dto.setDate(item.path("adate").asText() + "-" + selectdate);
                }

                departures.add(dto.toDepart());
            }

            // 저장
            departureRepository.saveAll(departures);

        } catch (ChatException e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.ERROR_TO_SAVE_ARRIVAL_DATA);
        } catch (Exception e) {
            log.error("출국장 데이터 저장 예외 발생: {}", e.getMessage(), e);
        }
    }

    /*
        바뀔 수 있는 값 : estimatedDatetime, flightId, gateNumber, ** remark, terminalId
    */
    @Transactional
    public void getPlane() {

        LocalDateTime today = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        List<String> searchDates = List.of(
                today.format(formatter),          // 오늘 (D+0)
                today.plusDays(1).format(formatter),  // 내일 (D+1)
                today.plusDays(2).format(formatter)   // 모레 (D+2)
        );

        String endPoint = "http://apis.data.go.kr/B551177/statusOfAllFltDeOdp/getFltDeparturesDeOdp";

        long openApiLoadingTime = 0L;

        try {

            for (String searchDate : searchDates) {
                String url = endPoint + "?"
                        + "serviceKey=" + API_KEY
                        + "&numOfRows=" + URLEncoder.encode("9999", StandardCharsets.UTF_8)
                        + "&type=" + URLEncoder.encode("json", StandardCharsets.UTF_8)
                        + "&searchDate=" + URLEncoder.encode(searchDate, StandardCharsets.UTF_8);

                URI uri = new URI(url);

                long startTime = System.currentTimeMillis();
                ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
                long executionTime = System.currentTimeMillis() - startTime;

                openApiLoadingTime += executionTime;

                updateOrSavePlaneData(response.getBody());
            }

            log.info("Api 로딩 시간 : " + openApiLoadingTime + "ms");

        } catch (ChatException e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.ERROR_TO_SAVE_PLANE_DATA);
        } catch (Exception e) {
            log.error("항공편 데이터 저장 예외 발생: {}", e.getMessage(), e);
        }
    }

    @Transactional
    private void updateOrSavePlaneData(String jsonData) {
        try {

            // jsonData가 "<"로 시작 한다는 에러가 발생하여 에러 처리
            if (jsonData == null || jsonData.isEmpty() || jsonData.startsWith("<")) {
                throw new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.ERROR_TO_CHANGE_JSON_DATE);
            }

            JsonNode root = objectMapper.readTree(jsonData);
            JsonNode items = root.path("response").path("body").path("items");

            ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();

            int updateDataCnt = 0;
            long redisFetchTime = 0;
            long dbFetchTime = 0;

            List<Plane> planesToUpdate = new ArrayList<>();  // 업데이트 할 데이터 리스트
            List<Plane> newPlanes = new ArrayList<>();  // 새롭게 추가할 데이터 리스트

            for (JsonNode item : items) {

                String flightId = item.path("flightId").asText();
                String codeshare = item.path("codeshare").asText(); // 본 항공편만 조회
                String remark = item.path("remark").asText(); // 출발 여부
                String scheduleDatetime = item.path("scheduleDatetime").asText(); // 항공편 예정 시간

                String estimatedDatetime = item.path("estimatedDatetime").asText(); // 항공편 수정 시간
                String gateNumber = item.path("gateNumber").asText(); // 항공편 탑승 gate 번호
                String terminalId = item.path("terminalId").asText();

                // 현재 보다 출발 예정 시각이 이전 이면서 "출발" 상태인 것은 x + Master가 아니면 x, 이미 출발한 항공편은 그냥 넘김
                if (!codeshare.equals("Master")) {
                    continue;
                }

                String redisKey = "plane:" + flightId + ":" + scheduleDatetime;

                Plane redisPlane = null;

                try {
                    String planeJson = (String) redisTemplate.opsForValue().get(redisKey);

                    if (planeJson != null) {
                        long redisStartTime = System.currentTimeMillis();
                        redisPlane = objectMapper.readValue(planeJson, Plane.class);
                        redisFetchTime += (System.currentTimeMillis() - redisStartTime);
                    }
                } catch (JsonProcessingException e) {
                    log.error("JSON 역직렬화 실패", e);
                }

                Plane existingPlane = null;

                // Redis에 해당 데이터가 없다면
                if (redisPlane == null) {
                    long dbStartTime = System.currentTimeMillis();
                    existingPlane = planeRepository.findByFlightIdAndScheduleDatetime(flightId, scheduleDatetime);
                    dbFetchTime += (System.currentTimeMillis() - dbStartTime);
                } else { // Redis에 있다면
                    existingPlane = redisPlane;
                }

                // 데이터가 존재 하지 x, 새로운 데이터이므로 DB와 Redis에 저장
                if (existingPlane == null) {

                    PlaneDto dto = PlaneDto.builder()
                            .flightId(item.path("flightId").asText())
                            .airLine(item.path("airline").asText())
                            .airport(item.path("airport").asText())
                            .airportCode(item.path("airportCode").asText())
                            .scheduleDatetime(item.path("scheduleDatetime").asText())
                            .estimatedDatetime(item.path("estimatedDatetime").asText())
                            .gateNumber(item.path("gateNumber").asText())
                            .terminalId(item.path("terminalId").asText())
                            .remark(item.path("remark").asText())
                            .aircraftRegNo(item.path("aircraftRegNo").asText())
                            .codeShare(item.path("codeshare").asText())
                            .build();

                    Plane newPlane = dto.toPlane();

                    boolean isDuplicate = newPlanes.stream()
                            .anyMatch(p -> p.getFlightId().equals(flightId) && p.getScheduleDatetime().equals(scheduleDatetime));
                    if (isDuplicate) continue;

                    valueOps.set(redisKey, objectMapper.writeValueAsString(newPlane), 1, TimeUnit.HOURS);

                    newPlanes.add(newPlane);

                } else { // 데이터가 존재 하면 변경 사항 확인 후 업데이트

                    if (planeChangeCheck(existingPlane, remark, estimatedDatetime, gateNumber, terminalId)) {

                        existingPlane.updatePlane(remark, estimatedDatetime, gateNumber, terminalId);

                        planesToUpdate.add(existingPlane);

                        updateDataCnt++;
                    }

                    valueOps.set(redisKey, objectMapper.writeValueAsString(existingPlane), 1, TimeUnit.HOURS);
                }
            }

            if (!newPlanes.isEmpty()) {
                planeRepository.saveAll(newPlanes);
            }

            if (!planesToUpdate.isEmpty()) {
                planeRepository.saveAll(planesToUpdate);
            }

            log.info("업데이트 된 항공편 개수 : " + updateDataCnt);
            if (redisFetchTime != 0) {
                log.info("Redis에서 데이터 불러오는 총 시간: " + redisFetchTime + "ms");
            } else {
                log.info("DB에서 데이터 불러오는 총 시간: " + dbFetchTime + "ms");
            }

        } catch (ChatException e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.ERROR_TO_SAVE_PLANE_DATA);
        } catch (Exception e) {
            log.error("항공편 데이터 저장 예외 발생: {}", e.getMessage(), e);
        }
    }

    private boolean planeChangeCheck(Plane existingPlane, String remark , String estimatedDatetime, String gateNumber, String terminalId) {

        return !existingPlane.getRemark().equals(remark) || !existingPlane.getEstimatedDatetime().equals(estimatedDatetime) ||
                !existingPlane.getGateNumber().equals(gateNumber) || !existingPlane.getTerminalId().equals(terminalId);
    }

    // 출국장 데이터 조회
    public List<DepartureResDto> getDepartures() {

        List<Departure> departures = departureRepository.findAll();

        return departures.stream()
                .map(departure -> DepartureResDto.builder()
                        .date(departure.getDate())
                        .timeZone(departure.getTimeZone())
                        .t1Depart12(departure.getT1Depart12())
                        .t1Depart3(departure.getT1Depart3())
                        .t1Depart4(departure.getT1Depart4())
                        .t1Depart56(departure.getT1Depart56())
                        .t1DepartSum(departure.getT1DepartSum())
                        .t2Depart1(departure.getT2Depart1())
                        .t2Depart2(departure.getT2Depart2())
                        .t2DepartSum(departure.getT2DepartSum())
                        .build())
                .collect(Collectors.toList());
    }

    // 항공편 데이터 조회 , 레디스 조회 후 없으면 DB에서 조회
    public List<PlaneResDto> getAllPlanes() {

        // 우선 redis에서 항공편 데이터 조회
        Set<String> keys = redisTemplate.keys("plane:*");

        if (!keys.isEmpty()) {
            List<Object> redisPlanes = redisTemplate.opsForValue().multiGet(keys);

            List<PlaneResDto> planesFromRedis = redisPlanes.stream()
                    .filter(Objects::nonNull)
                    .map(obj -> {
                        try {
                            return objectMapper.readValue((String) obj, PlaneResDto.class);
                        } catch (JsonProcessingException e) {
                            log.error("Redis에서 PlaneResDto 변환 중 오류 발생", e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (!planesFromRedis.isEmpty()) {
                log.info("Redis에서 Plane 데이터 조회");
                return planesFromRedis;
            }
        }

        // Redis에 데이터가 없으면 DB에서 조회
        List<Plane> planes = planeRepository.findAll();

        // 조회한 데이터를 Redis에 캐싱
        ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();

        planes.forEach(plane -> valueOps.set("plane:" + plane.getFlightId() + ":" + plane.getScheduleDatetime(), plane, 1, TimeUnit.HOURS));

        log.info("DB에서 Plane 데이터 조회");

        return planes.stream()
                .map(plane -> PlaneResDto.builder()
                        .flightId(plane.getFlightId())
                        .airLine(plane.getAirLine())
                        .airport(plane.getAirport())
                        .airportCode(plane.getAirportCode())
                        .scheduleDatetime(plane.getScheduleDatetime())
                        .estimatedDatetime(plane.getEstimatedDatetime())
                        .gateNumber(plane.getGateNumber())
                        .terminalId(plane.getTerminalId())
                        .remark(plane.getRemark())
                        .aircraftRegNo(plane.getAircraftRegNo())
                        .codeShare(plane.getCodeShare())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public void PlaneDelAndIst() {

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String yesterday = LocalDateTime.now().minusDays(1).format(formatter);

        try {
            long deleteCnt = planeRepository.deleteByScheduleDateStartsWith(yesterday);
            log.info( "삭제된 어제 항공편 데이터 개수 : " + deleteCnt );
            log.info("어제 항공편 데이터 삭제 성공");
        } catch (ChatException e) {
            log.info("어제 항공편 데이터 삭제 실패");
        }
    }

    public Page<DepartureResDto> testPage(int page, int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        return departureRepository.findAll(pageable).map(DepartureResDto::new);

    }
}
