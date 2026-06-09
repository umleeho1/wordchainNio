# 끝말잇기 게임 서버 (Java NIO)

Java NIO 기반의 멀티룸 끝말잇기 게임 서버입니다. Reactor 패턴을 활용해 단일 스레드 Selector로 다수의 클라이언트 연결을 처리하고, Worker 스레드 풀로 게임 로직을 비동기 처리합니다.

## 아키텍처

```
                        ┌─────────────────────────────┐
                        │       KkuGameServer          │
                        │   (NIO Selector / Reactor)   │
                        └───────────┬─────────────────┘
                 OP_ACCEPT          │ OP_READ / OP_WRITE
                 ┌──────────────────┴──────────────────┐
                 ▼                                     ▼
          ┌──────────────┐                   ┌──────────────────┐
          │  RoomManager │                   │  ClientHandler   │
          │ (매치메이킹) │                   │ (NIO I/O 핸들러) │
          └──────┬───────┘                   └────────┬─────────┘
                 │ findOrCreateRoom()                 │ onMessage(data)
                 ▼                                    ▼
          ┌──────────────┐                  ┌──────────────────────┐
          │  GameSession │◄─────────────────│    Worker Thread     │
          │ (방/세션 관리│  broadcast()      │    Pool              │
          └──────┬───────┘                  └──────────────────────┘
                 │
                 ▼
          ┌──────────────────┐
          │  WordChainRules  │
          │ (게임 룰 엔진)   │
          └──────────────────┘
```

## 주요 클래스

| 클래스 | 역할 |
|---|---|
| `KkuGameServer` | NIO 이벤트 루프(Reactor Pattern) 실행, 서버 진입점 |
| `ClientHandler` | 클라이언트 1:1 담당, 비차단 Read/Write, 송신 큐 관리 |
| `GameSession` | 게임 방 단위 세션 — 턴 제어, 타이머, 브로드캐스트 |
| `RoomManager` | 멀티룸 매치메이킹 — 빈 방 탐색 및 생성 |
| `WordChainRules` | 끝말잇기 규칙 검증, 점수 집계, 단어 히스토리 |
| `KoreanWordChecker` | 국립국어원 우리말샘 Open API로 단어 유효성 검증 |
| `FastSerializer` | ThreadLocal 버퍼 재사용 직렬화 — GC Pause 최소화 |

## 게임 규칙

- 방당 **최대 5명** 입장 시 자동 게임 시작
- **턴 제한 시간**: 11초 (초과 시 -20점, 판 초기화)
- **게임 총 시간**: 299초
- **점수**: 단어 성공 +10점, 시간 초과 -20점
- 단어 조건: 순수 한글, 2글자 이상, 이전 단어의 끝 글자로 시작, 최근 50개 단어 중복 불가
- **스팸 방지**: 슬라이딩 윈도우 방식 (5초 내 20개 초과 시 경고)

## 기술적 특징

**NIO Reactor 패턴**
- 단일 Selector 스레드가 모든 채널의 I/O 이벤트를 감지
- 게임 로직은 `WorkerPool`(CPU 코어 × 2)에 위임하여 네트워크 I/O와 비즈니스 로직 분리

**TCP Fragmentation 대응**
- 패킷 앞에 4바이트 길이 헤더를 붙여 전송
- 데이터가 끊겨 도착해도 완전한 패킷이 조립될 때까지 버퍼링

**GC 최적화 (FastSerializer)**
- `ThreadLocal`로 스레드별 전용 직렬화 버퍼 캐싱 (Lock-free)
- `ExposedBAOS`로 내부 버퍼 배열을 직접 참조 — `toByteArray()` 복사 비용 제거

**좀비 커넥션 처리**
- 50초마다 Heartbeat 체크 실행
- 마지막 응답으로부터 100초 경과 시 강제 퇴장

**멀티룸 파티셔닝**
- `RoomManager`가 방을 동적 생성/제거
- 빈 방 제거로 메모리 누수 방지

## 실행 방법

**빌드**
```bash
mvn package
```

**실행**
```bash
java -jar target/local-0.0.1-SNAPSHOT.jar
```

서버는 기본 **5000번 포트**에서 대기합니다.

## 환경

- Java 8
- Maven
- 의존성: `org.json:json:20220924`
- 단어 검증: [국립국어원 우리말샘 Open API](https://opendict.korean.go.kr)

## 패킷 프로토콜

클라이언트-서버 간 통신은 Java 직렬화(`ObjectOutputStream`)를 사용합니다.

```
[ 4 bytes: payload length ][ N bytes: serialized Message object ]
```

`Message` 객체 필드:

| 필드 | 타입 | 설명 |
|---|---|---|
| `type` | `MsgType` | SYSTEM / CHAT / GAME_DATA / ERROR / LEAVE |
| `sender` | String | 발신자 이름 |
| `content` | String | 메시지 내용 또는 단어 |
| `score` | Map<String, Integer> | 전체 점수 현황 |
| `isTurn` | boolean | 수신자의 현재 턴 여부 |
| `w` | `WordStatus` | 단어 검증 결과 |
