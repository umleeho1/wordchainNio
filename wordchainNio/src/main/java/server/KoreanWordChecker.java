package server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Pattern;

import org.json.JSONObject;

// [책임] 끝말잇기 게임의 단어 유효성을 검증하기 위해 국립국어원(우리말샘) Open API와 통신하는 외부 연동 유틸리티
// [Why] 수십만 개의 단어가 담긴 무거운 사전 파일(DB나 텍스트)을 서버 메모리에 직접 올려두는 대신, 외부 공식 API를 활용하여 서버의 힙 메모리를 절약하고 항상 최신 표준어 데이터를 반영하기 위함
public class KoreanWordChecker {
    // 우리말샘 Open API 인증키
    private static final String API_KEY = "39270BF6023CC995E610567A14FC58FB";

    // [책임] 정규식을 이용한 1차 입력값 필터링 (순수 한글 여부 검사)
    // [Why] 숫자, 영어, 특수문자 등 명백히 규칙에 어긋나는 입력값에 대해서는 무거운 외부 API HTTP 호출을 아예 생략함으로써, 불필요한 네트워크 I/O 지연을 막고 API 일일 호출 제한(Quota)을 아끼기 위함
    private static final Pattern KOREAN_ONLY = Pattern.compile("^[가-힣]+$");

    // [책임] 클라이언트가 입력한 단어를 외부 API에 검색(GET 요청)하고, 응답 결과(JSON)를 파싱하여 실제 존재하는 단어인지 판별
    // [Why] 이 메서드는 HTTP 네트워크 통신(동기 방식)을 수행하므로 시간이 오래 걸릴 수 있습니다. 따라서 메인 NIO Selector 스레드가 멈추지 않도록 반드시 Worker 스레드 풀 내부에서 호출되어야 합니다.
    public static boolean isValidWord(String word) {
        try {
            // 한글만 허용 (영어, 일본어, 숫자, 특수문자, 띄어쓰기 불가)
            if (!KOREAN_ONLY.matcher(word).matches()) {
                return false;
            }
            
            // 검색어 URL 인코딩
            String encodedWord = URLEncoder.encode(word, "UTF-8");
            
            // 우리말샘 API 요청 주소 생성
            String urlStr = "https://opendict.korean.go.kr/api/search" + "?key=" + API_KEY + "&q=" + encodedWord
                    + "&req_type=json";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            // API 응답 데이터 읽기
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));

            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();

            // JSON 파싱 후 검색 결과 개수 확인
            JSONObject json = new JSONObject(sb.toString());
            JSONObject channel = json.getJSONObject("channel");
            int total = channel.getInt("total");

            // 검색 결과가 1개 이상이면 유효한 단어로 판정
            return total > 0;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}