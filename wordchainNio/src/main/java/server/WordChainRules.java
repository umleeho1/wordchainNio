package server;

import dto.WordStatus;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

// [책임] 끝말잇기 게임의 핵심 비즈니스 로직(단어 검증, 점수 기록, 중복 체크)을 처리하는 도메인 모델
// [Why] 네트워크 I/O나 세션 관리 로직과 순수 게임 규칙을 분리(관심사 분리)하여, 게임 룰이 변경되더라도 서버의 코어 네트워크 로직에 영향을 주지 않도록 결합도를 낮추기 위함
public class WordChainRules {
    
    // [책임] 유저별 실시간 점수 저장 및 관리
    // [Why] 여러 워커 스레드가 동시에 점수를 업데이트하거나 조회할 때 데이터가 꼬이는 현상을 방지하기 위해, 내부적으로 락(Lock) 분할 기술을 사용하여 성능 저하를 최소화한 ConcurrentHashMap을 채택함
    private final ConcurrentHashMap<String, Integer> scoreMap = new ConcurrentHashMap<>();
    
    // 마지막으로 입력된 단어
    private String lastWord = "";
    
    // [책임] 중복 단어 입력을 방지하기 위한 단어 히스토리 큐
    private final Queue<String> wordHistory = new LinkedList<>();
    
    // [책임] 히스토리에 저장할 최대 단어 수 설정 (메모리 제한)
    // [Why] 게임이 무한정 길어질 경우 수만 개의 단어가 큐에 쌓여 서버의 힙 메모리를 고갈(OOM)시키는 것을 막기 위해, 오래된 단어를 밀어내는 슬라이딩 윈도우 방식을 적용함
    private final int RECENTLY_WORD_LIMIT = 50;

    // [책임] 클라이언트가 제출한 단어가 끝말잇기 룰(길이, 사전, 중복, 이어말하기)에 완벽히 부합하는지 단계별로 검증
    // [Why] 검증 도중 다른 스레드가 `lastWord`나 `wordHistory`를 변경하여 오판하는 것을 막기 위해 `synchronized` 키워드로 원자성(Atomicity)을 보장함
    public synchronized WordStatus checkWord(String word) {
        word = word.trim();

        // 1. 길이 검사 (두 글자 이상만 허용)
        if (word.length() < 2) {
            return WordStatus.TOO_SHORT;
        }

        // 2. 사전 존재 여부 검사 (KoreanWordChecker API 연동)
        if (!KoreanWordChecker.isValidWord(word)) {
            return WordStatus.NO_WORD;
        }

        // 3. 중복 검사 (최근에 사용한 단어인지 확인)
        if (wordHistory.contains(word)) {
            return WordStatus.RECENTLY;
        }

        // 4. 끝말잇기 규칙 검사 (첫 단어가 아닐 경우에만 수행)
        if (!lastWord.isEmpty()) {
            char lastChar = lastWord.charAt(lastWord.length() - 1); // 이전 단어의 마지막 글자
            char firstChar = word.charAt(0);                      // 현재 단어의 첫 글자

            if (lastChar != firstChar) {
                return WordStatus.WRONG;
            }
        }

        // 모든 통관 절차 완료!
        return WordStatus.SUCCESS;
    }

    // [책임] 검증을 통과한 단어를 공식적인 '마지막 단어'로 갱신하고 히스토리 큐에 추가
    public synchronized void updateWordHistory(String word) {
        wordHistory.add(word);
        
        // 히스토리 크기 제한 (오래된 단어는 삭제)
        if (wordHistory.size() > RECENTLY_WORD_LIMIT) {
            wordHistory.poll();
        }
        
        this.lastWord = word;
    }

    // [책임] 특정 유저에게 점수를 부여하거나 차감
    // [Why] 단순히 `get()` 후 `put()`을 하면 그 찰나에 다른 스레드가 개입할 수 있으므로, `compute()` 메서드를 사용하여 조회와 연산(업데이트)을 하나의 원자적(Atomic) 작업으로 묶어 완벽한 스레드 안전성을 확보함
    public int addScore(String name, int pt) {
        // 이름이 없으면 0점 리턴, 있으면 기존 점수에 합산
        scoreMap.compute(name, (k, v) -> (v == null) ? pt : v + pt);
        return scoreMap.getOrDefault(name, 0);
    }

    // [책임] 현재 점수판의 스냅샷(복사본)을 생성하여 반환
    // [Why] 원본 객체(ConcurrentHashMap)의 참조를 그대로 넘기면, 브로드캐스트를 위해 직렬화하는 도중에 점수가 바뀌어 불일치나 `ConcurrentModificationException`이 발생할 수 있으므로 방어적 복사(Defensive Copy)를 수행함
    public Map<String, Integer> getAllScores() {
        return new HashMap<>(scoreMap);
    }

    // [책임] 게임 종료 또는 방 초기화 시 진행 데이터를 리셋하여 다음 게임을 준비함
    public synchronized void resetData() {
        lastWord = "";
        wordHistory.clear();
        scoreMap.clear();
    }

    public String getLastWord() {
        return lastWord;
    }

    // [책임] 유저 퇴장 시 점수판에서 해당 유저의 데이터를 깔끔하게 제거
    public void removeScore(String name) {
        scoreMap.remove(name);
    }
}