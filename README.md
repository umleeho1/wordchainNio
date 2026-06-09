# 끄투 - 한국어 끝말잇기 게임 서버 (Java NIO)

Java NIO 기반의 고성능 멀티룸 끝말잇기 게임 서버입니다.  
Reactor 패턴을 적용하여 단일 스레드 Selector로 다수의 클라이언트 연결을 처리하고, Worker Pool을 통해 게임 로직을 비동기로 실행합니다.

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| Language | Java 8 |
| Build | Maven |
| Network | Java NIO (Non-blocking I/O) |
| Pattern | Reactor Pattern (Selector) |
| 외부 API | 국립국어원 우리말샘 Open API |
| 직렬화 | Java Object Serialization + 커스텀 FastSerializer |

---

## 아키텍처 개요

```
[클라이언트들]
      │  TCP (Port 5000)
      ▼
┌───────────────────────────────────────────────┐
│             KkuGameServer (메인 진입점)         │
│     Selector (NIO 이벤트 루프 / Reactor)        │
│   ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│   │OP_ACCEPT │  │OP_READ   │  │OP_WRITE    │  │
│   └──────────┘  └──────────┘  └────────────┘  │
└───────────────────────────────────────────────┘
              │                      │
    [ClientHandler]        [Worker Thread Pool]
    (소켓 I/O 담당)        (CPU cores × 2, 게임 로직)
              │
    ┌─────────┴──────────┐
    │     RoomManager     │  ← 멀티룸 매치메이킹
    └─────────────────────┘
              │
    ┌─────────┴──────────┐
    │     GameSession     │  ← 방별 턴/타이머 관리
    └─────────────────────┘
              │
    ┌─────────┴──────────┐
    │   WordChainRules    │  ← 끝말잇기 비즈니스 로직
    └─────────────────────┘
              │
    ┌─────────┴──────────┐
    │ KoreanWordChecker   │  ← 우리말샘 API 단어 검증
    └─────────────────────┘
```

---

## 패키지 구조

```
src/main/java/
├── server/
│   ├── KkuGameServer.java       # 메인 서버 (NIO Selector, Reactor 루프)
│   ├── ClientHandler.java       # 클라이언트별 NIO I/O 핸들러
│   ├── GameSession.java         # 방(Room) 생명주기 및 턴 제어
│   ├── RoomManager.java         # 멀티룸 생성/탐색/삭제 (매치메이킹)
│   ├── WordChainRules.java      # 끝말잇기 게임 규칙 (단어 검증, 점수)
│   └── KoreanWordChecker.java   # 우리말샘 API 단어 유효성 검사
├── dto/
│   ├── Message.java             # 클라이언트↔서버 메시지 DTO (Serializable)
│   ├── MsgType.java             # 메시지 타입 Enum
│   └── WordStatus.java          # 단어 검증 결과 Enum
└── util/
    └── FastSerializer.java      # 고성능 직렬화 (ThreadLocal + Zero-copy)
```

---

## 주요 클래스

| 클래스 | 역할 |
|--------|------|
| `KkuGameServer` | NIO 이벤트 루프(Reactor Pattern) 실행, 서버 진입점 |
| `ClientHandler` | 클라이언트 1:1 담당, 비차단 Read/Write, 송신 큐 관리 |
| `GameSession` | 게임 방 단위 세션 — 턴 제어, 타이머, 브로드캐스트 |
| `RoomManager` | 멀티룸 매치메이킹 — 빈 방 탐색 및 생성 |
| `WordChainRules` | 끝말잇기 규칙 검증, 점수 집계, 단어 히스토리 |
| `KoreanWordChecker` | 국립국어원 우리말샘 Open API로 단어 유효성 검증 |
| `FastSerializer` | ThreadLocal 버퍼 재사용 직렬화 — GC Pause 최소화 |

---

## 게임 규칙 및 서버 설정

| 설정 | 값 |
|------|----|
| 포트 | `5000` |
| 방 최대 인원 | `5명` |
| 턴 제한 시간 | `11초` |
| 게임 전체 제한 시간 | `299초` |
| 단어 히스토리 (중복 방지) | 최근 `50개` |
| Heartbeat 타임아웃 | `100초` |
| 도배 방지 | `5초 내 최대 20개` |

### 점수 규칙

| 상황 | 점수 |
|------|------|
| 단어 성공 | **+10** |
| 턴 타임아웃 | **-20** (판 초기화) |

---

## 단어 검증 흐름

```
클라이언트 입력
      │
      ▼
[1] 한글 정규식 검사  (^[가-힣]+$)
      │ 실패 → WRONG
      ▼
[2] 두 글자 이상 길이 검사
      │ 실패 → TOO_SHORT
      ▼
[3] 우리말샘 API 사전 검색
      │ 실패 → NO_WORD
      ▼
[4] 최근 50개 단어 중복 검사
      │ 실패 → RECENTLY
      ▼
[5] 끝말잇기 규칙 (이전 단어 마지막 글자 == 현재 단어 첫 글자)
      │ 실패 → WRONG
      ▼
    SUCCESS (+10점)
```

---

## 핵심 기술 및 설계 결정

### NIO Reactor 패턴
`Selector` 하나로 수천 개의 소켓 이벤트(Accept/Read/Write)를 단일 스레드에서 감지하고, 실제 비즈니스 로직은 `Worker Thread Pool`에 위임합니다.  
네트워크 I/O와 게임 로직을 분리하여 Selector 스레드가 블로킹되지 않도록 합니다.

### TCP Fragmentation 대응 (Length-Prefix 프로토콜)
TCP 특성상 데이터가 분할되어 도착할 수 있으므로, 4바이트 길이 헤더를 먼저 읽고 해당 길이만큼 Payload가 모두 도착할 때까지 버퍼링합니다.

```
[ 4 bytes: payload length ][ N bytes: serialized Message object ]
```

### FastSerializer (ThreadLocal + Zero-copy)
- `ThreadLocal<FastSerializer>`로 스레드별 독립 인스턴스 유지 → Lock-free 직렬화
- `ExposedBAOS`로 `toByteArray()` 배열 복사 제거 → Zero-copy 지향
- `ReusableObjectOutputStream.reset()`으로 버퍼 재사용 → GC 압력 최소화

### 동시성 자료구조

| 자료구조 | 사용 이유 |
|----------|----------|
| `CopyOnWriteArrayList` | 클라이언트 리스트: 순회(Read) 빈도가 높아 읽기 최적화 |
| `ConcurrentHashMap` | 점수 맵: `compute()`로 조회+갱신을 원자적 처리 |
| `ConcurrentLinkedQueue` | 송신 큐: Lock-free 삽입/삭제 |

### Zombie 클라이언트 탐지 (Heartbeat)
50초마다 모든 클라이언트의 마지막 응답 시간을 검사하여, `100초` 이상 응답이 없으면 강제 퇴장 처리합니다.  
FIN 패킷 없이 비정상 종료된 연결로 인한 메모리 누수를 방지합니다.

### 도배 방지 (Sliding Window)
슬라이딩 윈도우 알고리즘으로 `5초 내 20개` 초과 메시지를 보내는 클라이언트에게 ERROR를 반환합니다.

### 멀티룸 파티셔닝
`RoomManager`가 방을 동적 생성/제거합니다. 빈 방은 주기적으로 제거하여 메모리 누수를 방지합니다.

---

## 패킷 프로토콜 (Message DTO)

`Message` 객체 필드:

| 필드 | 타입 | 설명 |
|------|------|------|
| `type` | `MsgType` | 메시지 종류 |
| `sender` | `String` | 발신자 이름 |
| `content` | `String` | 메시지 내용 또는 단어 |
| `score` | `Map<String, Integer>` | 전체 점수 현황 |
| `isTurn` | `boolean` | 수신자의 현재 턴 여부 |
| `w` | `WordStatus` | 단어 검증 결과 |

### MsgType

| 값 | 설명 |
|----|------|
| `SYSTEM` | 서버 알림 (입장, 퇴장, 게임 시작 등) |
| `CHAT` | 일반 채팅 메시지 |
| `GAME_DATA` | 게임 실시간 데이터 (턴 정보, 제시어, 점수) |
| `ERROR` | 단어 규칙 위반 또는 도배 경고 |
| `LEAVE` | 유저 퇴장 이벤트 |

### WordStatus

| 값 | 설명 |
|----|------|
| `SUCCESS` | 단어 성공 |
| `TOO_SHORT` | 두 글자 미만 |
| `WRONG` | 끝말잇기 규칙 위반 |
| `RECENTLY` | 최근에 사용한 단어 |
| `NO_WORD` | 사전에 없는 단어 |
| `NONE` | 해당 없음 (일반 채팅) |

---

## 빌드 및 실행

### 요구사항
- Java 8 이상
- Maven 3.x

### 빌드

```bash
mvn clean package
```

`target/` 폴더에 실행 가능한 fat JAR이 생성됩니다.

### 실행

```bash
java -jar target/local-0.0.1-SNAPSHOT.jar
```

서버가 **5000번 포트**에서 대기합니다.

```
>>> 끄투 게임 서버(NIO 멀티룸) 준비 완료. 포트: 5000
```

---

## 의존성

```xml
<dependency>
    <groupId>org.json</groupId>
    <artifactId>json</artifactId>
    <version>20220924</version>
</dependency>
```

우리말샘 API 응답 JSON 파싱에 사용합니다.

---

## 외부 API

**국립국어원 우리말샘 Open API**
- 엔드포인트: `https://opendict.korean.go.kr/api/search`
- 용도: 입력 단어가 실제 한국어 사전에 존재하는지 검증
- API 키는 `KoreanWordChecker.java`의 `API_KEY` 상수에서 관리합니다.

---

## 클라이언트 (client/)

Java Swing 기반 GUI 클라이언트입니다. `client/` 폴더에 위치합니다.

### 클라이언트 패키지 구조

```
client/src/main/java/
├── main/
│   └── Main.java                # 진입점 (SwingUtilities로 GUI 시작)
├── controller/
│   ├── MainController.java      # 화면 전환 및 서버 연결 관리
│   ├── GameController.java      # 게임 메시지 수신 처리
│   ├── RankController.java      # 랭킹 로직
│   └── TowerController.java     # 탑쌓기 게임 로직
├── front/
│   ├── MenuView.java            # 메인 메뉴 화면
│   ├── WordChainView.java       # 끝말잇기 게임 화면
│   ├── RankingView.java         # 랭킹 화면
│   └── TowerBreakView.java      # 탑쌓기 게임 화면
├── service/
│   ├── NetworkService.java      # 서버 TCP 연결, 송수신 스레드
│   ├── GameService.java         # 게임 상태 관리
│   └── RankService.java         # 랭킹 서비스
├── repository/
│   ├── WordRepository.java      # 단어 데이터 접근
│   ├── RankRepository.java      # 랭킹 데이터 접근
│   └── TowerRepository.java     # 탑쌓기 데이터 접근
└── dto/
    ├── Message.java             # 서버와 공유하는 메시지 DTO
    ├── MsgType.java             # 메시지 타입 Enum
    ├── WordStatus.java          # 단어 검증 결과 Enum
    ├── RankDTO.java             # 랭킹 데이터 DTO
    └── TowerDTO.java            # 탑쌓기 데이터 DTO
```

### 네트워크 통신 (NetworkService)

서버의 Length-Prefix 프로토콜에 맞춰 `DataOutputStream` / `DataInputStream`으로 통신합니다.

- **송신**: `sendQueue`(BlockingQueue)에 메시지를 넣으면 SenderThread가 직렬화 후 전송
- **수신**: ReceiverThread가 4바이트 길이 → Payload 순서로 읽어 역직렬화

```
기본 접속 정보: 127.0.0.1:5000
```

### 클라이언트 실행

```bash
cd client
mvn compile exec:java -Dexec.mainClass="main.Main"
```
