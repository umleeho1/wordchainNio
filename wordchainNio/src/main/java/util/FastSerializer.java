package util;

import java.io.*;
import java.nio.ByteBuffer;

// [책임] 객체 직렬화 시 발생하는 메모리 할당 및 가비지 컬렉션(GC) 오버헤드를 극단적으로 줄인 고성능 커스텀 직렬화 유틸리티
// [Why] 초당 수만 개의 메시지가 브로드캐스트되는 게임 서버에서 매번 새로운 바이트 배열과 ObjectOutputStream을 생성하면 힙 메모리가 급격히 팽창하고 GC Pause(Stop-The-World)가 발생하므로, 객체와 버퍼를 재사용(Pooling)하여 서버의 응답 속도를 일정하게 유지하기 위함
public class FastSerializer {

    // [책임] 워커 스레드별로 완전히 독립적인 FastSerializer 인스턴스를 보장(캐싱)
    // [Why] 직렬화 버퍼(ExposedBAOS)는 상태를 가지므로 멀티스레드 환경에서 안전하지 않습니다(Thread-unsafe). 동기화(Lock)를 걸어 스레드 병목을 만드는 대신, ThreadLocal을 사용해 각 스레드가 자신만의 전용 도구를 가지게 하여 락-프리(Lock-free) 병렬 처리를 극대화함
    private static final ThreadLocal<FastSerializer> INSTANCE = 
            ThreadLocal.withInitial(() -> new FastSerializer());

    private final ExposedBAOS bos = new ExposedBAOS(4096);
    private ReusableObjectOutputStream oos; // 커스텀 스트림 사용

    // [책임] 스레드당 최초 1회만 내부 버퍼와 스트림을 초기화하여 메모리에 할당
    private FastSerializer() {
        try {
            // 최초 생성 시 bos와 연결
            this.oos = new ReusableObjectOutputStream(bos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static FastSerializer getInstance() {
        return INSTANCE.get();
    }

    // [책임] 주어진 객체를 바이트 배열로 변환하고 4바이트 길이 정보(Header)를 맨 앞에 붙여 완전한 ByteBuffer 패킷으로 조립
    // [Why] bos와 oos를 새로 만들지 않고 초기화(reset)하여 재사용함으로써, 매 패킷 전송마다 발생하는 임시 객체(Garbage) 생성을 0(Zero)에 가깝게 만듦
    public ByteBuffer serialize(Object obj) throws IOException {
        bos.reset(); // 버퍼 인덱스를 0으로 돌림 (기존 배열 공간 재사용)
        
        // oos의 내부 캐시(Handle Table)를 비우고 헤더를 다시 쓰게 함
        // (주의: ObjectOutputStream은 참조했던 객체를 내부 Map에 기억하므로 reset하지 않으면 메모리 누수가 발생함)
        oos.reset(); 
        oos.writeStreamHeader(); // 매 패킷이 독립적인 스트림으로 인식되도록 헤더 삽입

        oos.writeObject(obj);
        oos.flush();

        int dataSize = bos.size();
        
        // 네트워크 전송을 위한 최종 ByteBuffer 할당 (4바이트 길이 + 실제 데이터)
        ByteBuffer buffer = ByteBuffer.allocate(4 + dataSize);
        buffer.putInt(dataSize);
        
        // [핵심 최적화] toByteArray() 대신 내부 버퍼 배열을 직접 가져와서 씀
        buffer.put(bos.getInternalBuffer(), 0, dataSize);
        buffer.flip();
        
        return buffer;
    }

    // [책임] 매 패킷마다 스트림 헤더를 강제로 다시 쓸 수 있도록 ObjectOutputStream을 확장한 클래스
    // [Why] ObjectOutputStream은 본래 파일이나 긴 연결 하나에 한 번만 헤더(매직 넘버: AC ED 00 05)를 쓰도록 설계되었습니다. 하지만 우리는 UDP나 짧은 패킷 단위로 쪼개서 보내기 때문에, 클라이언트 측 역직렬화기가 올바르게 인식할 수 있도록 `reset()` 후 헤더를 강제로 주입해야 함
    private static class ReusableObjectOutputStream extends ObjectOutputStream {
        public ReusableObjectOutputStream(OutputStream out) throws IOException {
            super(out);
        }

        @Override
        public void writeStreamHeader() throws IOException {
            // 부모의 메서드를 호출하여 헤더를 씀
            super.writeStreamHeader();
        }
    }

    // [책임] 내부 바이트 배열(buf)에 외부에서 직접 접근할 수 있도록 열어둔(Exposed) 확장 스트림
    // [Why] 기본 ByteArrayOutputStream의 `toByteArray()` 메서드는 호출 시마다 내부 배열과 똑같은 크기의 배열을 새로 복사(Deep Copy)해서 반환합니다. 이는 엄청난 메모리 낭비이므로, 내부 `buf`의 주소 참조를 직접 반환하게 만들어 배열 복사 비용(Zero-copy 지향)을 완벽히 제거함
    private static class ExposedBAOS extends ByteArrayOutputStream {
        public ExposedBAOS(int size) { super(size); }
        public byte[] getInternalBuffer() { return buf; }
    }
}