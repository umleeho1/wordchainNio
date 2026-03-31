package server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Pattern;

import org.json.JSONObject;

public class KoreanWordChecker {
	// 우리말샘 Open API 인증키
	private static final String API_KEY = "39270BF6023CC995E610567A14FC58FB";

	// 한글만 허용하는 정규식
	private static final Pattern KOREAN_ONLY = Pattern.compile("^[가-힣]+$");

	// 다른 클래스에서 호출할 단어 유효성 검사 메서드
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