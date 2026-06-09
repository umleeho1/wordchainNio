package front;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.BevelBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.SoftBevelBorder;
import javax.swing.border.TitledBorder;

import controller.GameController;
import service.NetworkService;

public class WordChainView extends JFrame {
	private static final long serialVersionUID = 1L;
    // [필드 승격] 컨트롤러가 접근해야 하는 컴포넌트들
    private JLabel wordLb;
    private JProgressBar ptmBar, gtmBar;
    private DefaultListModel<String> chatMdl;
    private JList<String> chatLst;
    private JTextField chatin;
    private JButton startBtn, sendBtn;
    
    // 플레이어 정보 표시용 배열 (최대 5명)
    private JLabel[] nameLbs = new JLabel[5];
    private JLabel[] scoreLbs = new JLabel[5];
    private JPanel[] playerPnels = new JPanel[5];

    private Timer gTm;
    private int pTmCount = 100; //유저턴타이머 10초*10 (0.1초 단위) =100
    private int gTmCount = 3000; //전체타이머 300초*10(0.1초단위) = 3000
    
    // 게임 종료 후 메뉴 복귀 타이머 중복 실행 방지
    private boolean menuReturnScheduled = false;

    private static WordChainView inst = new WordChainView();
    public static WordChainView getInstance() { return inst; }

    private WordChainView() {
        setTitle("Word Chain Game");
        setSize(800, 800);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setResizable(false);
        setLayout(new BorderLayout(10, 10));

        // 외곽 여백 패널
        JPanel contentPn = new JPanel(new BorderLayout(15, 15));
        contentPn.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        setContentPane(contentPn);

        // --- 상단 영역 (제시어 & 타이머) ---
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        // 제시어 (컨트롤러가 바꿀 수 있게 필드로 저장)
        wordLb = new JLabel("대기 중...", SwingConstants.CENTER);
        wordLb.setFont(new Font("맑은 고딕", Font.BOLD, 45));
        wordLb.setForeground(Color.white);
        wordLb.setBackground(new Color(41, 128, 185));
        wordLb.setOpaque(true);
        wordLb.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createBevelBorder(BevelBorder.RAISED),
                BorderFactory.createEmptyBorder(10, 50, 10, 50)
        ));
        JPanel wordWrap = new JPanel();
        wordWrap.add(wordLb);

        // 타이머 설정
        JLabel ptmLb = new JLabel("개인 제한 시간");
        ptmLb.setAlignmentX(Component.CENTER_ALIGNMENT);
        ptmBar = new JProgressBar(0, 100);
        ptmBar.setValue(100);
        ptmBar.setForeground(Color.orange);
        ptmBar.setPreferredSize(new Dimension(600, 25));

        JLabel gtmLb = new JLabel("전체 남은 시간");
        gtmLb.setAlignmentX(Component.CENTER_ALIGNMENT);
        gtmBar = new JProgressBar(0, 100);
        gtmBar.setValue(100);
        gtmBar.setForeground(Color.red);
        gtmBar.setPreferredSize(new Dimension(600, 25));

        topPanel.add(wordWrap);
        topPanel.add(Box.createVerticalStrut(20));
        topPanel.add(ptmLb);
        topPanel.add(ptmBar);
        topPanel.add(Box.createVerticalStrut(10));
        topPanel.add(gtmLb);
        topPanel.add(gtmBar);

        // --- 중앙 영역 (플레이어 정보 & 채팅) ---
        JPanel centerContainer = new JPanel(new BorderLayout(0, 15));
        
        // 플레이어 패널 (최대 5명)
        JPanel playPanel = new JPanel(new GridLayout(1, 5, 15, 0));
        playPanel.setPreferredSize(new Dimension(800, 140));
        Color[] pColors = {
                new Color(255, 235, 238), new Color(232, 245, 233),
                new Color(227, 242, 253), new Color(255, 248, 225),
                new Color(243, 229, 245)
        };

        for (int i = 0; i < 5; i++) {
            playerPnels[i] = new JPanel(new GridLayout(2, 1));
            playerPnels[i].setBorder(BorderFactory.createCompoundBorder(
                    new SoftBevelBorder(BevelBorder.LOWERED),
                    BorderFactory.createEmptyBorder(10, 5, 10, 5)
            ));
            playerPnels[i].setBackground(pColors[i]);
            playerPnels[i].setOpaque(true);

            nameLbs[i] = new JLabel("Empty", SwingConstants.CENTER);
            nameLbs[i].setFont(new Font("돋음", Font.BOLD, 16));
            scoreLbs[i] = new JLabel("Score: 0", SwingConstants.CENTER);
            scoreLbs[i].setFont(new Font("Monospaced", Font.PLAIN, 14));

            playerPnels[i].add(nameLbs[i]);
            playerPnels[i].add(scoreLbs[i]);
            playPanel.add(playerPnels[i]);
        }

        // 채팅창 영역
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(new TitledBorder(new LineBorder(Color.GRAY), "실시간 채팅/로그"));
        chatPanel.setPreferredSize(new Dimension(800, 250));

        chatMdl = new DefaultListModel<>();
        chatLst = new JList<>(chatMdl);
        chatLst.setFont(new Font("굴림", Font.PLAIN, 13));
        JScrollPane scrollPn = new JScrollPane(chatLst);

        // 채팅 입력부
        JPanel inPanel = new JPanel(new BorderLayout(5, 0));
        inPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        chatin = new JTextField();
        chatin.setFont(new Font("맑은 고딕", Font.PLAIN, 16));
        chatin.setPreferredSize(new Dimension(0, 40));
        chatin.setEnabled(false); // 게임 시작 전까진 비활성화

        sendBtn = new JButton("Send");
        sendBtn.setPreferredSize(new Dimension(80, 40));
        sendBtn.setEnabled(false);

        inPanel.add(chatin, BorderLayout.CENTER);
        inPanel.add(sendBtn, BorderLayout.EAST);
        chatPanel.add(scrollPn, BorderLayout.CENTER);
        chatPanel.add(inPanel, BorderLayout.SOUTH);

        centerContainer.add(playPanel, BorderLayout.NORTH);
        centerContainer.add(chatPanel, BorderLayout.CENTER);

        // --- 하단 시작 버튼 ---
        startBtn = new JButton("GAME START");
        startBtn.setFont(new Font("Arial Black", Font.BOLD, 25));
        startBtn.setBackground(Color.darkGray);
        startBtn.setForeground(Color.white);
        startBtn.setPreferredSize(new Dimension(800, 60));
        startBtn.setFocusPainted(false);

        // 컴포넌트 배치
        add(topPanel, BorderLayout.NORTH);
        add(centerContainer, BorderLayout.CENTER);
        add(startBtn, BorderLayout.SOUTH);
        // 생성자 내부 (setupEvents() 호출 전쯤에 넣어주세요)
        gTm = new Timer(100, e -> timerTick()); // 0.1초마다 timerTick() 호출
        setupEvents();

        // 윈도우 닫기 이벤트
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitClient(); // X버튼 종료 시 서버에 먼저 퇴장 신호 전송
            }
        });
    }

    // [컨트롤러 전용 메서드 1] 채팅창 업데이트
    public void appendChat(String msg) {
        chatMdl.addElement(msg);
        chatLst.ensureIndexIsVisible(chatMdl.getSize() - 1);
    }

    // [컨트롤러 전용 메서드 2] 제시어 업데이트
    public void updateWord(String word) {
        wordLb.setText("제시어: " + word);
    }

    // [컨트롤러 전용 메서드 3] 점수판 업데이트 및 턴 하이라이트
    public void updateGameState(Map<String, Integer> scores, String currentTurnPlayer) {
        int i = 0;
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (i >= 5) break;
            String playerName = entry.getKey();
            nameLbs[i].setText(playerName);
            scoreLbs[i].setText("Score: " + entry.getValue());
            
            // 턴인 사람 강조
            if (playerName.equals(currentTurnPlayer)) {
                playerPnels[i].setBorder(new TitledBorder(new LineBorder(Color.RED, 3), "TURN"));
                playerPnels[i].setBackground(new Color(255, 235, 235)); // 살짝 붉은 배경
            } else {
                playerPnels[i].setBorder(new SoftBevelBorder(BevelBorder.LOWERED));
                playerPnels[i].setBackground(new Color(240, 240, 240)); // 일반 배경
            }
            i++;
        }
    }

    // [컨트롤러 전용 메서드 4] 입력창 제어
    public void setInputEnable(boolean enable) {
        chatin.setEnabled(enable);
        sendBtn.setEnabled(enable);
        if (enable) {
            chatin.requestFocus();
            chatin.setBorder(new LineBorder(Color.BLUE, 2));
        } else {
            chatin.setBorder(new LineBorder(Color.GRAY, 1));
        }
    }

    // [컨트롤러 전용 메서드 5] 타이머 시작 (서버 신호 받아서 실행)
    public void startLocalTimer() {
        pTmCount = 100;
        ptmBar.setValue(100);
        if (gTm != null) gTm.start();
        startBtn.setEnabled(false);
        startBtn.setText("PLAYING...");
    }

    public void showMessage(String title, String msg) {
        JOptionPane.showMessageDialog(this, msg, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private void setupEvents() {
        ActionListener sendAction = e -> {
            String text = chatin.getText().trim();
            if (!text.isEmpty()) {
                // 이제 sendChat, sendWord 구분 없이 그냥 전송!
                GameController.getInstance().sendWord(text); 
                chatin.setText("");
            }
        };
        chatin.addActionListener(sendAction);
        sendBtn.addActionListener(sendAction);
    }
    /**
     * 서버에서 받은 실시간 점수판 데이터를 UI에 반영합니다.
     */
    public void updatePlayerUI(Map<String, Integer> scoreMap) {
        // 1. 이름 목록을 리스트로 뽑아냅니다.
        List<String> playerNames = new ArrayList<>(scoreMap.keySet());

        // 2. 5개의 칸을 돌면서 데이터를 채웁니다.
        for (int i = 0; i < 5; i++) {
            if (i < playerNames.size()) {
                // 접속한 사람이 있는 칸
                String name = playerNames.get(i);
                int score = scoreMap.get(name);
                
                nameLbs[i].setText(name);         // "Empty" 대신 실제 이름!
                scoreLbs[i].setText("Score: " + score);
            } else {
                // 아직 비어있는 칸
                nameLbs[i].setText("Empty");
                scoreLbs[i].setText("Score: 0");
            }
        }
    }
    public void start() {
        resetViewState(); // 메뉴에서 다시 들어올 때 이전 상태 초기화
        setLocationRelativeTo(null);
        setVisible(true);
    }
 // WordChainView 내부의 타이머 구동 핵심 로직
    public void resetAndStartTimer(int seconds) {
        if (gTm == null) {
            // 혹시나 초기화가 안 되어 있다면 여기서라도 생성
            gTm = new Timer(100, e -> timerTick());
        }

        if (gTm.isRunning()) gTm.stop();

        this.pTmCount = seconds * 10; 
        this.ptmBar.setMaximum(this.pTmCount);
        this.ptmBar.setValue(this.pTmCount);
        this.ptmBar.setForeground(Color.GREEN); // 시작은 초록색
        
        gTm.start(); 
    }

    // 타이머가 0.1초마다 실행할 내용 (생성자나 별도 메서드에 있을 것)
    private void timerTick() {
        // 1. 개인 턴 타이머 로직
        if (pTmCount > 0) {
            pTmCount--;
            ptmBar.setValue(pTmCount);
            
            if (pTmCount < 30) { // 3초 남았을 때 빨간색
                ptmBar.setForeground(Color.RED);
            } else {
                ptmBar.setForeground(Color.GREEN);
            }
        }

        // 2. 전체 게임 타이머 로직 (핵심 수정)
        if (gTmCount > 0) {
            gTmCount--;
            gtmBar.setValue(gTmCount); // 이제 최대치가 3000이라 줄어드는 게 보입니다!
            
            // [선택 사항] 전체 타이머에 남은 시간을 숫자로 표시하고 싶을 때
            int seconds = gTmCount / 10;
            gtmBar.setString(String.format("%02d:%02d", seconds / 60, seconds % 60));
            gtmBar.setStringPainted(true); // 숫자 보이게 설정
        } else {
            gTm.stop();
            // 전체 시간 종료 처리는 서버가 추방하며 처리함
        }
    }
    // 게임 시작 시 GameController에서 호출할 메서드
    public void initGlobalTimer(int totalSeconds) {
        this.gTmCount = totalSeconds * 10; // 300초 -> 3000틱
        this.gtmBar.setMaximum(this.gTmCount); // 바의 최대치를 3000으로 설정!
        this.gtmBar.setValue(this.gTmCount);
        
        if (gTm != null && !gTm.isRunning()) {
            gTm.start();
        }
    }

    // 창 닫기(X버튼)로 종료 시 서버에 먼저 퇴장 신호를 보낸 뒤 프로그램 종료
    public void exitClient() {
        if (gTm != null) gTm.stop();
        NetworkService.getInstance().disconnectGracefully();
        dispose();
        System.exit(0);
    }

    // 게임 종료 시 몇 초 뒤 메인 메뉴로 복귀시키는 메서드
    public void returnToMenuAfterDelay(int seconds) {
        if (menuReturnScheduled) return;
        menuReturnScheduled = true;

        if (gTm != null) gTm.stop();
        setInputEnable(false);
        appendChat("▶ [안내] " + seconds + "초 후 메인 메뉴로 돌아갑니다.");

        Timer returnTimer = new Timer(seconds * 1000, e -> {
            ((Timer) e.getSource()).stop();

            // 게임 종료 흐름은 이미 끝난 게임이므로 LEAVE를 따로 보내지 않고 연결만 정리
            NetworkService.getInstance().stop();
            resetViewState();
            setVisible(false);
            MenuView.getInstance().start();

            menuReturnScheduled = false;
        });
        returnTimer.setRepeats(false);
        returnTimer.start();
    }

    // 메뉴 복귀 또는 재입장 시 화면 상태를 초기화하는 메서드
    public void resetViewState() {
        if (gTm != null) gTm.stop();

        wordLb.setText("대기 중...");
        chatin.setText("");
        chatMdl.clear();
        setInputEnable(false);

        pTmCount = 100;
        gTmCount = 3000;

        ptmBar.setMaximum(100);
        ptmBar.setValue(100);
        ptmBar.setForeground(Color.ORANGE);

        gtmBar.setMaximum(100);
        gtmBar.setValue(100);
        gtmBar.setString("");
        gtmBar.setStringPainted(false);

        for (int i = 0; i < 5; i++) {
            nameLbs[i].setText("Empty");
            scoreLbs[i].setText("Score: 0");
            playerPnels[i].setBorder(BorderFactory.createCompoundBorder(
                    new SoftBevelBorder(BevelBorder.LOWERED),
                    BorderFactory.createEmptyBorder(10, 5, 10, 5)
            ));
            playerPnels[i].setBackground(new Color(240, 240, 240));
        }

        startBtn.setEnabled(true);
        startBtn.setText("GAME START");
    }

}