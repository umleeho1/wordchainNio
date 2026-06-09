package repository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.XML;

// 한국어기초사전 API를 통해 게임에 사용할 단어 데이터셋을 생성
public class WordRepository {
	private static WordRepository wr = null; 
	private Set<String> words = new HashSet<>();
	private String apiKey = "E82F13A757BD361232D04919DEB80626"; // 한국어기초사전 API키
	// 병렬 처리를 위한 15개의 스레드가 들어갈 수 있는 스레드 풀 생성
	private final ExecutorService executor = Executors.newFixedThreadPool(15);
	
	private WordRepository() {};
	
	// 싱글톤 패턴
	public static WordRepository getInstance() {
		if(wr == null) {
			wr = new WordRepository();
		}
		return wr;
	}
	
	public Set<String> make() {
		// 병렬 작업에서 중요한 Set 사용 (중복 방지)
		Set<String> wordsCopy = new CopyOnWriteArraySet<>(words);
		
		while(wordsCopy.size() < 300) {
			int batchSize = 10; // 한 번에 시도할 요청 개수
			List<CompletableFuture<Void>> future = new ArrayList<>();
			
			for(int i=0; i<batchSize; i++) {
				future.add(CompletableFuture.runAsync(() -> {
					try {
						fetchWord(wordsCopy); // 파싱 로직을 별도의 메소드로 분리
					} catch(Exception e) {}; // 요청 실패는 무시
				}, executor));
			}
			CompletableFuture.allOf(future.toArray(new CompletableFuture[0])).join();
		}
		this.words = wordsCopy;
		executor.shutdown();
		return words;
	}
	
	
	// 게임에 사용할 단어 300개를 한국어기초사전 API로 추출해 Set에 저장하여 반환
	public void fetchWord(Set<String> wordsCopy) throws Exception {
		if(wordsCopy.size() >= 300) {
			return;
		}
		
		// 1. URL 설정 및 한글 인코딩
		String keyword = URLEncoder.encode(getKeyWord(), "UTF-8");
		String apiUrl = "https://krdict.korean.go.kr/api/search?key="
					+ apiKey + "&q=" + keyword + "&num=15";
		
		URL url = new URL(apiUrl);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		conn.setConnectTimeout(3000); // 연결하는 데 걸리는 최대 시간 (이 시간이 지나면 타임아웃)
		conn.setReadTimeout(3000); // 읽는데 걸리는 최대 시간 (이 시간이 지나면 타임아웃)
		
		// 2. 응답 데이터 읽기(XML)
		BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
		StringBuilder sb = new StringBuilder();
		String line;
		while((line = rd.readLine()) != null) {
			sb.append(line);
		}
		
		// 3. XML을 JSON으로 변환
		JSONObject json = XML.toJSONObject(sb.toString());
		JSONArray items = json.getJSONObject("channel").getJSONArray("item");
		
		for(int j=0; j<items.length(); j++) {
			String word = items.getJSONObject(j).getString("word");
			// 특수문자나 숫자가 포함된 단어는 제외하는 필터링
			if(wordsCopy.size() < 300 && word.matches("^[가-힣]*$")) {
				wordsCopy.add(word);
			}
		}
		
		// 열은 객체 닫기 (반환)
		rd.close();
		conn.disconnect();
	}
	
	// HTTPS 요청 시, 검색에 사용할 시작 단어 무작위로 생성
	private String getKeyWord() {
		Random rand = new Random();
		
		int base = 0xAC00;
		int choseong = rand.nextInt(19); // 초성
		int jungseong = rand.nextInt(21); // 중성
		int jongseong = 0; // 종성
		
		// 유니코드 방식으로 글자 조합하기
		char result = (char) (base + (choseong * 21 * 28) + (jungseong * 28) + jongseong);
		return String.valueOf(result); // 인코딩하기 위해 String 타입으로 변경
	}
}