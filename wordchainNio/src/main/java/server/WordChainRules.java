package server;

import dto.WordStatus;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 끝말잇기 규칙(단어 유효성 검사 및 점수 관리)을 담당하는 클래스
 */
public class WordChainRules {
    // 유저별 점수 저장 (멀티스레드 환경을 고려해 ConcurrentHashMap 사용)
    private final ConcurrentHashMap<String, Integer> scoreMap = new ConcurrentHashMap<>();
    
    // 마지막으로 입력된 단어
    private String lastWord = "";
    
    // 중복 단어 입력을 방지하기 위한 단어 히스토리 큐
    private final Queue<String> wordHistory = new LinkedList<>();
    
    // 히스토리에 저장할 최대 단어 수 (메모리 관리용)
    private final int RECENTLY_WORD_LIMIT = 50;

    /**
     * 입력된 단어가 규칙에 맞는지 검사하는 핵심 메서드
     */
    public synchronized WordStatus checkWord(String word) {
        word = word.trim();

        // 1. 길이 검사 (두 글자 이상만 허용)
        if (word.length() < 2) {
            return WordStatus.TOO_SHORT;
        }

//        // 2. 사전 존재 여부 검사 (KoreanWordChecker 연동)
//        // [수정] 주석을 해제하여 국립국어원 API로 실제 단어인지 확인합니다.
//        if (!KoreanWordChecker.isValidWord(word)) {
//            return WordStatus.NO_WORD;
//        }

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

    /**
     * 검증에 성공한 단어를 히스토리에 기록하고 마지막 단어로 갱신
     */
    public synchronized void updateWordHistory(String word) {
        wordHistory.add(word);
        
        // 히스토리 크기 제한 (오래된 단어는 삭제)
        if (wordHistory.size() > RECENTLY_WORD_LIMIT) {
            wordHistory.poll();
        }
        
        this.lastWord = word;
    }

    /**
     * 유저에게 점수를 부여하거나 차감
     */
    public int addScore(String name, int pt) {
        // 이름이 없으면 0점 리턴, 있으면 기존 점수에 합산
        scoreMap.compute(name, (k, v) -> (v == null) ? pt : v + pt);
        return scoreMap.getOrDefault(name, 0);
    }

    /**
     * 현재 모든 유저의 점수 현황을 반환
     */
    public Map<String, Integer> getAllScores() {
        return new HashMap<>(scoreMap);
    }

    /**
     * 게임 종료 시 데이터를 초기화
     */
    public synchronized void resetData() {
        lastWord = "";
        wordHistory.clear();
        scoreMap.clear();
    }

    public String getLastWord() {
        return lastWord;
    }

    /**
     * 유저 퇴장 시 점수판에서 제거
     */
    public void removeScore(String name) {
        scoreMap.remove(name);
    }
}