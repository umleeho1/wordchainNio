package repository;

import dto.TowerDTO;

public class TowerRepository {
	private static TowerRepository tr = null;
	private int combo;
	private int block;
	private int score;
	
	private TowerRepository() {};
	
	public static TowerRepository getInstance() {
		if(tr == null) {
			tr = new TowerRepository();
		}
		return tr;
	}
	
	// 게임 시작 시 화면에 표시되는 정보 초기화
	public void reset() {
		this.combo = 0;
		this.block = 0;
		this.score = 0;
	}
	
	// 콤보 횟수 증가
	public void addCombo() {
		this.combo++;
	}
	
	// 콤보 초기화
	public void resetCombo() {
		this.combo = 0;
	}
	
	// 파괴한 블록 수 증가
	public void addBlock() {
		this.block++;
	}
	
	// 점수 증가
	public void addScore(String word) {
		this.score += 400; // 파괴시 기본 점수
		if(combo > 2) { // 콤보가 3이상이라면 추가 점수
			this.score += 40 * (combo - 2);
		}
		if(word.length() > 2) { // 글자가 3글자 이상이라면 추가 점수
			this.score += 30 * (word.length() - 2);
		}
	}
	
	// 게임 화면에 표시되는 정보를 출력하기 위해 DTO에 정보를 담아 넘김
	public TowerDTO makeInfo() {
		TowerDTO tower = new TowerDTO(this.combo, this.block, this.score);
		return tower;
	}
}
