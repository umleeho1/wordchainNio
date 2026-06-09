package front;

import java.awt.Color;
import java.awt.Font;
import java.awt.SystemColor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

import controller.TowerController;

public class TowerBreakView extends JFrame {
	private static final long serialVersionUID = 1L;
	private static TowerBreakView inst = new TowerBreakView();
	private TowerController wdCtrl = TowerController.getInstance();
	
	private JLabel[] wordSlots = new JLabel[7]; 
	private String[] slotDatas = new String[7]; 
	private JTextField inField;
	private JButton sendBtn, startBtn;
	private JLabel scoreVal, comboVal, blockVal, idVal, timeVal;
	private JProgressBar gtmBar;
	private Timer gTimer; 
	
	private int wordIndex = 0;
	private int totalTime; 
	private final int MAX_GAME_TIME = 120; // 2분
	List<String> wdList;

	public static TowerBreakView getInstance() {
		return inst;
	}

	private TowerBreakView() {
		setTitle("Tower Break View");
		setSize(800, 800); 
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		setResizable(false);
		getContentPane().setLayout(null); // 절대 좌표
		getContentPane().setBackground(SystemColor.window);

		//상단 제목
		JLabel titleLb = new JLabel("TOWER BREAK", SwingConstants.CENTER);
		titleLb.setOpaque(true);
		titleLb.setBackground(new Color(100, 149, 237)); // CornflowerBlue
		titleLb.setForeground(Color.WHITE);
		titleLb.setFont(new Font("굴림", Font.BOLD, 25)); 
		titleLb.setBounds(200, 20, 400, 50); 
		titleLb.setBorder(new LineBorder(Color.BLACK, 1));
		getContentPane().add(titleLb);

		// 프로그레스 바
		gtmBar = new JProgressBar(0, MAX_GAME_TIME); 
		gtmBar.setValue(MAX_GAME_TIME); 
		gtmBar.setBounds(100, 90, 600, 30); 
		gtmBar.setForeground(new Color(255, 215, 0)); // Gold
		gtmBar.setBorder(new LineBorder(Color.GRAY, 1));
		
		JLabel progLb = new JLabel("남은 시간", SwingConstants.CENTER);
		progLb.setFont(new Font("굴림", Font.PLAIN, 12));
		progLb.setBounds(100, 90, 600, 30);
		getContentPane().add(progLb);
		getContentPane().add(gtmBar);

		//좌측/우측 정보 패널
		// ID
		idVal = new JLabel("ID: ", SwingConstants.LEFT);
		setStatStyle(idVal, Color.WHITE, 30, 150, 150, 30);
		
		// TIME, SCORE (좌측)
		createStatBox(this, "TIME", 30, 190, 120, 80);
		createStatBox(this, "SCORE", 30, 280, 120, 80);
		
		// COMBO, BLOCK (우측)
		createStatBox(this, "COMBO", 650, 190, 120, 80);
		createStatBox(this, "BLOCK", 650, 280, 120, 80);
		
		getContentPane().add(idVal);

		// 중앙
		JPanel wordPanel = new JPanel(null);
		wordPanel.setBounds(180, 150, 440, 440); 
		wordPanel.setBackground(Color.WHITE); // 내부는 흰색
		wordPanel.setBorder(new LineBorder(Color.BLACK, 2)); 
		
		JLabel wordTitle = new JLabel("WORD", SwingConstants.CENTER);
		wordTitle.setOpaque(true);
		wordTitle.setBackground(new Color(255, 255, 200)); // 연노랑
		wordTitle.setFont(new Font("굴림", Font.BOLD, 14));
		wordTitle.setBounds(310, 140, 180, 25); 
		wordTitle.setBorder(new LineBorder(Color.BLACK, 1));
		getContentPane().add(wordTitle);
		
		// 지그재그 단어 라벨 7개
		for (int i = 0; i < 7; i++) {
			wordSlots[i] = new JLabel("", SwingConstants.CENTER);
			wordSlots[i].setSize(200, 35); 
			wordSlots[i].setOpaque(true);
			wordSlots[i].setFont(new Font("돋움", Font.PLAIN, 16));
			wordSlots[i].setBackground(new Color(245, 245, 245)); // 아주 밝은 회색
			//얇은 선 테두리
			wordSlots[i].setBorder(new LineBorder(Color.LIGHT_GRAY, 1));
			
			// 지그재그 좌표
			int x = (i % 2 == 0) ? 40 : 200; 
			int y = 40 + (i * 55); 
			wordSlots[i].setLocation(x, y);
	        if (i == 6) { // 맨 아래 단어 (7번째)
	            wordSlots[i].setFont(new Font("돋움", Font.BOLD, 20)); // 굵게
	            wordSlots[i].setBackground(new Color(100, 149, 237)); // 파란색 배경
	            wordSlots[i].setForeground(Color.WHITE); // 흰색 글자
	        } else {
	            wordSlots[i].setFont(new Font("돋움", Font.PLAIN, 16));
	            wordSlots[i].setBackground(new Color(245, 245, 245));
	            wordSlots[i].setForeground(Color.BLACK);
	        }		
			wordPanel.add(wordSlots[i]);
			slotDatas[i] = "";
		}
		getContentPane().add(wordPanel);

		//하단 입력창 및 버튼
		inField = new JTextField();
		inField.setFont(new Font("돋움", Font.PLAIN, 16));
		inField.setBounds(180, 610, 340, 40); 
		inField.setBorder(new LineBorder(Color.GRAY, 1));
		inField.addActionListener(e -> processWord());
		getContentPane().add(inField);
		
		sendBtn = new JButton("Send");
		sendBtn.setFont(new Font("굴림", Font.PLAIN, 12));
		sendBtn.setBounds(530, 615, 80, 30); 
		sendBtn.addActionListener(e -> processWord());
		getContentPane().add(sendBtn);

		startBtn = new JButton("게임 시작 (GAME START)");
		startBtn.setBounds(180, 670, 440, 50); 
		startBtn.setFont(new Font("굴림", Font.BOLD, 18));
		startBtn.addActionListener(e -> gameStart());
		getContentPane().add(startBtn);

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) { showExitDialog("게임을 중단하시겠습니까?"); }
		});
	}
	
	public void start() {
		setLocationRelativeTo(null);
		setVisible(true);
	    idVal.setText("ID: " + wdCtrl.getID());
	}

	private void createStatBox(JFrame frame, String title, int x, int y, int w, int h) {
		JPanel p = new JPanel(null);
		p.setBounds(x, y, w, h);
		p.setBackground(Color.WHITE);
		p.setBorder(new LineBorder(Color.BLACK, 1));
		
		JLabel tLb = new JLabel(title, SwingConstants.CENTER);
		tLb.setFont(new Font("굴림", Font.PLAIN, 12)); 
		tLb.setBounds(0, 0, w, 20); 
		p.add(tLb);
		
		JLabel valLb = new JLabel("0", SwingConstants.CENTER);
		valLb.setBounds(5, 25, w - 10, h - 30); 
		valLb.setFont(new Font("Arial", Font.BOLD, 20)); 
		valLb.setOpaque(true);
		valLb.setBackground(new Color(240, 255, 240)); // 아주 옅은 초록색
		valLb.setBorder(new LineBorder(Color.LIGHT_GRAY, 1));
		p.add(valLb);
		
		if(title.equals("TIME")) {
			timeVal = valLb;
			timeVal.setText(String.valueOf(MAX_GAME_TIME)); 
		}
		else if(title.equals("SCORE")) scoreVal = valLb;
		else if(title.equals("COMBO")) comboVal = valLb;
		else if(title.equals("BLOCK")) blockVal = valLb;
		
		frame.getContentPane().add(p);
	}
	
	private void setStatStyle(JLabel lb, Color c, int x, int y, int w, int h) {
		lb.setOpaque(true);
		lb.setBackground(c);
		lb.setFont(new Font("돋움", Font.PLAIN, 14)); 
		lb.setBounds(x, y, w, h);
		lb.setBorder(new CompoundBorder(new LineBorder(Color.BLACK, 1), new EmptyBorder(0, 5, 0, 5)));
	}

	private void gameStart() {
		resetGame();
		inField.setEnabled(true);
		sendBtn.setEnabled(true);
		inField.requestFocus();
	    startBtn.setText("게임 재시작 (GAME RESTART)");
    	wdList = wdCtrl.getWordList();
    	Collections.shuffle(wdList);
    	
    	// 7개 단어채우고 시작
		for(int i=0; i<7; i++) {
			slotDatas[i] = wdList.get(wordIndex++ % wdList.size());
		}
		
		updateUI();
	    gtmBar.setMaximum(MAX_GAME_TIME); 
		gtmBar.setValue(MAX_GAME_TIME);    
		totalTime = MAX_GAME_TIME;        
		timeVal.setText(String.valueOf(MAX_GAME_TIME)); 
		
		gTimer = new Timer(1000, e -> {
			totalTime--;
			gtmBar.setValue(totalTime);
			gtmBar.setValue(totalTime+1);
			timeVal.setText(String.valueOf(totalTime));
			if (totalTime <= 0) {
				gTimer.stop();
				showExitDialog("제한 시간 종료!");
			}
		});
		gTimer.start();
	}

	private void processWord() {
	    String input = inField.getText().trim();
	    inField.setText("");
	    String target = slotDatas[6];//맨 아래 단어
	    boolean isCorrect = wdCtrl.checkWord(target, input);
	    if (isCorrect) {
	        // 맞추면 하강 로직 실행
	        for (int j = 6; j > 0; j--) {
	            slotDatas[j] = slotDatas[j-1];
	        }
	        // 새 단어 보충
	        slotDatas[0] = wdList.get(wordIndex++ % wdList.size());
	    }
	    
//	    int matchIdx = wdCtrl.checkAllSlots(slotDatas, input);
//	    if (matchIdx != -1) {
//	        // [핵심] 맞춘 칸(matchIdx)을 기준으로 그 위의 단어들만 하강
//	        for (int j = matchIdx; j > 0; j--) {
//	            slotDatas[j] = slotDatas[j - 1];
//	        }
//	        
//	        // 새 단어 보충 (기존 index 유지 로직)
//	        slotDatas[0] = wdList.get(wordIndex++ % wdList.size());
//	    }

	    // 뷰 갱신 (점수 변화 감지 및 표시)
	    updateUI();
	}

	private void resetGame() {
		if (gTimer != null) gTimer.stop();
		wordIndex = 0;  //주석 처리 하면 index 를 이어서 하게됨.
		for (int i = 0; i < 7; i++) { slotDatas[i] = ""; }
		wdCtrl.prepareGame();
		updateUI();
	}

	private void updateUI() {
		for (int i = 0; i < 7; i++) {
	        wordSlots[i].setText(slotDatas[i]);
	    }
		scoreVal.setText(String.valueOf(wdCtrl.getDTO().getScore()));
		comboVal.setText(String.valueOf(wdCtrl.getDTO().getCombo()));
		blockVal.setText(String.valueOf(wdCtrl.getDTO().getBlock()));
	}
	
	public void showExitDialog(String message) {
	    //타이머 정지
	    if (gTimer != null) {
	        gTimer.stop();
	    }
	    wdCtrl.addRanking(); //랭킹 저장 요청

	    //현재 점수
	    int score = wdCtrl.getDTO().getScore();
	    
	    //사용자에게 보여줄 메시지
	    String content = message + "\n최종 점수: " + score + "점\n\n다시 도전하시겠습니까?";
	    
	    //버튼 선택
	    Object[] options = {"다시하기", "메뉴로 가기"};

	    //다이얼로그 띄우기
	    int choice = JOptionPane.showOptionDialog(
	            this,               // 부모창
	            content,            // 내용
	            "GAME OVER",        // 제목
	            JOptionPane.YES_NO_OPTION, 
	            JOptionPane.QUESTION_MESSAGE, 
	            null, 
	            options, 
	            options[0]          // 기본값
	    );

	    //버튼 선택
	    if (choice == 0) {
	        gameStart(); 
	    } else {
	        this.setVisible(false);
	        MenuView.getInstance().setVisible(true);
	        startBtn.setEnabled(true);
	    }
	}	
	
}
