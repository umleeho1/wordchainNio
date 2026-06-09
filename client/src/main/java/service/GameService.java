package service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dto.TowerDTO;
import repository.TowerRepository;
import repository.WordRepository;

// 게임 서비스 시 필요한 작업을 수행
public class GameService {
	private WordRepository wr;
	private TowerRepository tr;
	
	public GameService() {
		wr = WordRepository.getInstance();
		tr = TowerRepository.getInstance();
	}
	
	// 게임에서 타워에 들어갈 단어 데이터셋 생성을 수행
	public List<String> makeWordSet() {
		Set<String> words = wr.make();
		List<String> wlist = new ArrayList<>(words);
		return wlist;
	}
	
	// 게임 시작 시 화면에 출력되는 정보 초기화
	public void prepareGame() {
		tr.reset();
	}
	
	// 게임 화면에 표시되는 정보 제공 (콤보수, 파괴한 블록 수, 점수)
	public TowerDTO makeInfo() {
		return tr.makeInfo();
	}
	
	// 입력한 단어와 제시된 단어가 일치하는지 확인
	public boolean checkWord(String target, String input) {
		if(target.equals(input)) {
			tr.addBlock();
			tr.addCombo();
			tr.addScore(target);
			return true;
		}
		tr.resetCombo();
		return false;
	}
}