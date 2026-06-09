package controller;

import java.util.List;

import dto.RankDTO;
import dto.TowerDTO;
import service.GameService;

public class TowerController {
	private static TowerController tc = new TowerController();
	private RankController rc = new RankController();
	private String id;
	private GameService gs = new GameService();
	
	private TowerController() {};
	
	public static TowerController getInstance() {
		if(tc == null) {
			tc = new TowerController();
		}
		return tc;
	}
	
	// 메인 뷰에서 입력된 사용자 아이디를 설정
	public void sendID(String id) {
		this.id = id;
	}
	
	// 사용자가 설정된 아이디를 요청
	public String getID() {
		return id;
	}
	
	// 사용자가 게임 시작 요청
	public void prepareGame() {
		gs.prepareGame();
	}
	
	// 사용자가 게임 화면 내 출력될 정보 요청
	public TowerDTO getDTO() {
		return gs.makeInfo();
	}
	
	// 사용자가 게임에 사용할 단어 데이터셋 요청
	public List<String> getWordList() {
		return gs.makeWordSet();
	}

	// 사용자가 입력한 단어가 맞는지 체크
	public boolean checkWord(String target, String input) {
		return gs.checkWord(target, input);
	}
	
	public void addRanking() {
		TowerDTO tower = gs.makeInfo();
		int score = tower.getScore();
		int block = tower.getBlock();
		
		RankDTO rank = new RankDTO(id, score, block);
		rc.addRank(rank);
	}
}
