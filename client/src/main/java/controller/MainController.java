package controller;

import javax.swing.JOptionPane;

import front.MenuView;
import front.RankingView;
import front.TowerBreakView;
import front.WordChainView;
import service.NetworkService;

public class MainController {
	private static MainController inst = new MainController();

	// 메뉴에서 입력한 ID 저장
	private String loginId;

	// 테스트용 서버 정보
	private static final String SERVER_IP = "127.0.0.1";
	private static final int SERVER_PORT = 5000;

	private MainController() {
	}

	public static MainController getInstance() {
		return inst;
	}

	// 프로그램 시작 시 메뉴 띄우기
	public void start() {
		MenuView.getInstance().start();
	}

	// ID 입력 처리
	public boolean submitId(String id) {
		if (id == null || id.trim().isEmpty()) {
			JOptionPane.showMessageDialog(null, "ID를 입력해주세요.");
			return false;
		}
		

		loginId = id.trim();
		JOptionPane.showMessageDialog(null, "Welcome " + loginId + "!");
		return true;
	}

	public String getLoginId() {
		return loginId;
	}

	// 끝말잇기 진입
	public void openWordChain() {
	    if (!checkLogin()) return;

	    // 1. 연결 시도 후 결과(boolean)를 받음
	    boolean isConnected = NetworkService.getInstance().connect(SERVER_IP, SERVER_PORT, loginId);

	    if (isConnected) {
	        // 2. 연결 성공 시에만 화면 전환
	        MenuView.getInstance().setVisible(false);
	        WordChainView.getInstance().start();
	    } else {
	        // 3. 실패 시 경고창 띄우고 메뉴에 그대로 남음
	        JOptionPane.showMessageDialog(null, "서버에 접속할 수 없습니다. (정원 초과 또는 서버 점검 중)");
	    }
	}

	// 탑쌓기 진입
	public void openTowerBreak() {
		if (!checkLogin())
			return;

		// 타워팀에서 sendID 구현되어 있다는 전제
		TowerController.getInstance().sendID(loginId);

		MenuView.getInstance().setVisible(false);
		TowerBreakView.getInstance().start();
	}

	// 랭킹 화면 진입
	public void openRanking() {
		if (!checkLogin())
			return;

		MenuView.getInstance().setVisible(false);
		RankingView.getInstance().start();
	}

	// 공통 로그인 체크
	private boolean checkLogin() {
		if (loginId == null || loginId.trim().isEmpty()) {
			JOptionPane.showMessageDialog(null, "먼저 ID를 입력해주세요.");
			return false;
		}
		return true;
	}
}