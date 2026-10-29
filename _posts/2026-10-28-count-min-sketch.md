---
layout: post
title: "키 56만 개를 56KB로 세면 무엇을 잃을까: 카운트-민 스케치 실험기"
date: 2026-10-28
category: CS Study
tags: [Tech, Redis, CountMinSketch, ProbabilisticDataStructure, Streaming, FDS]
excerpt: "이벤트 1천만 건을 Redis 8에서 세어 보니 ZSET은 57.1MB, HASH는 35.7MB, 카운트-민 스케치는 56KB였다. 대신 '1,000회 넘으면 의심' 판정의 1차 필터로 쓰면 기본 설정에서는 키의 80%를 다시 세야 했다. 숫자로 확인한 카운트-민 스케치의 쓸 곳과 못 쓸 곳."
---

> **TL;DR**: 이벤트 1천만 건(키 561,442개)을 Redis 8에서 세어 보니 ZSET 57.1MB, HASH 35.7MB, 카운트-민 스케치 56KB였다. 56만 개 키 중 실제보다 작게 센 키는 하나도 없었다. 그런데 "1,000회 넘으면 의심"이라는 규칙의 1차 필터로 쓰면 `CMS.INITBYPROB 0.001 0.01` 기본 크기로는 키의 80.2%를 다시 세야 했다. 오차 한계 ε·N이 임계값보다 한참 작아야 쓸모가 있다. 같은 54KB라도 보수적 갱신을 켜면 0.16%까지 줄었다.

---

## 1. 시작은 랭킹 글에서 남은 질문이었다

[9주차]({% post_url 2026-04-10-9weeksBlog %})에 Redis ZSET으로 실시간 랭킹을 만들었다. 상품 10만 개 정도라면 ZSET에 다 넣고 `ZINCRBY`로 올려도 아무 문제가 없었다.

그런데 송금 서비스 백엔드를 개발하면서 다른 종류의 숫자를 셀 일이 생겼다. IP별 요청 수, 기기별 로그인 시도 수처럼 키가 계속 늘어나는 숫자다. 상품은 몇만 개에서 멈추지만 IP는 끝이 없다.

이런 숫자는 대부분 정확할 필요가 없다. "이 IP가 오늘 1,203번 왔다"보다 "천 번을 넘었다"가 중요하다. 그렇다면 정확히 세느라 메모리를 다 쓰는 대신 대략 세는 방법이 있지 않을까 해서 찾다가 카운트-민 스케치(Count-Min Sketch)를 만났다.

개념 설명은 이미 좋은 글이 많아서 이번에는 직접 재 보기로 했다. 정말 메모리를 얼마나 아끼는지, 그 대가로 무엇을 잃는지.

---

## 2. 정확히 세면 얼마나 드나

실험 조건은 이렇다.

| 항목 | 값 |
|------|----|
| 이벤트 수 | 10,000,000건 |
| 키 후보 | 1,000,000개 (IP 형태 문자열) |
| 실제로 나온 키 | 561,442개 |
| 분포 | Zipf (s = 1.1), 소수의 키에 몰리고 대부분은 몇 번 안 나옴 |
| 가장 많이 나온 키 | 1,240,251번 |
| 중앙값 | 2번 |
| 실행 환경 | Node.js 24, Redis 8.10.3 (Docker), 시드 고정 |

실제 트래픽 대신 Zipf 분포로 만든 합성 데이터다. 웹 트래픽처럼 소수의 키에 몰리고 꼬리가 긴 모양을 흉내 냈다.

같은 결과를 Redis에 세 가지 방식으로 넣고 `MEMORY USAGE`로 쟀다.

![같은 키 561,442개를 세는 데 쓴 Redis 메모리](/assets/images/count-min-sketch/redis-memory.svg)

| 자료구조 | 메모리 |
|---------|--------|
| ZSET (`ZADD`) | 57,118,885 bytes (57.1MB) |
| HASH (`HSET`) | 35,723,959 bytes (35.7MB) |
| Count-Min Sketch (`CMS.INITBYPROB 0.001 0.01`) | 56,056 bytes (56KB) |

HASH의 약 640분의 1이다. 키가 100만 개, 1억 개로 늘어도 스케치 크기는 그대로다. 처음 크기를 정할 때 한 번 메모리를 잡고 끝이다.

---

## 3. 카운트-민 스케치

![카운트-민 스케치 구조](/assets/images/count-min-sketch/structure.svg)

구조는 d행 w열짜리 정수 배열 하나다. 행마다 서로 다른 해시 함수가 하나씩 붙는다.

- **넣을 때**: 키를 행마다 해시해서 나온 칸에 1을 더한다. d칸이 올라간다.
- **물을 때**: 같은 d칸을 읽어서 가장 작은 값을 답한다.

최솟값을 쓰는 이유는 충돌 때문이다. 한 칸에는 그 키뿐 아니라 해시가 겹친 다른 키의 개수도 더해져 있어서 어느 칸이든 실제 값 이상이다. 행마다 섞인 잡음이 다르니 가장 작은 칸이 실제에 가장 가깝다. 이름의 "Min"이 여기서 왔다.

덕분에 **덧셈만 하는 동안 실제보다 작게 세는 일은 없다.** 이 성질이 6절에서 중요해진다.

크기는 허용 오차 비율 ε와 실패 확률 δ로 정한다.

```
w = ⌈e / ε⌉,  d = ⌈ln(1/δ)⌉

확률 1−δ 이상으로:  실제값 ≤ 추정값 ≤ 실제값 + ε·N   (N = 전체 이벤트 수)
```

ε = 0.001, δ = 0.01이면 2719×5, 카운터 13,595개, 4바이트 카운터로 약 54KB다.

구현은 짧다. 해시 함수를 d개 따로 만들지 않고 두 개를 선형 조합하는 방식(Kirsch & Mitzenmacher)을 썼다.

```javascript
class CountMinSketch {
  constructor(width, depth, { conservative = false, salt = 0 } = {}) {
    this.width = width;
    this.depth = depth;
    this.conservative = conservative;
    this.salt = salt;
    this.table = new Uint32Array(width * depth);
    this.idx = new Uint32Array(depth);
  }

  indexes(key) {
    const h1 = fnv1a(key, 0x811c9dc5 ^ this.salt);
    const h2 = fnv1a(key, 0x01000193 ^ this.salt) | 1;
    for (let i = 0; i < this.depth; i++) {
      this.idx[i] = i * this.width + (((h1 + i * h2) >>> 0) % this.width);
    }
    return this.idx;
  }

  add(key, count = 1) {
    const idx = this.indexes(key);
    if (!this.conservative) {
      for (let i = 0; i < this.depth; i++) this.table[idx[i]] += count;
      return;
    }
    // 보수적 갱신: 현재 추정값 + count 보다 작은 칸만 그 값까지 올린다
    let min = Infinity;
    for (let i = 0; i < this.depth; i++) min = Math.min(min, this.table[idx[i]]);
    for (let i = 0; i < this.depth; i++) {
      if (this.table[idx[i]] < min + count) this.table[idx[i]] = min + count;
    }
  }

  estimate(key) {
    const idx = this.indexes(key);
    let min = Infinity;
    for (let i = 0; i < this.depth; i++) min = Math.min(min, this.table[idx[i]]);
    return min;
  }
}

function fnv1a(s, seed) {
  let h = seed >>> 0;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h;
}
```

### 직접 해 보기

4행 13열짜리 작은 스케치다. 칸이 적어서 충돌이 잦고 과대 추정이 잘 보인다. "단어 300건 흘려보내기"를 누르면 자주 나온 단어와 드문 단어의 오차 차이가 막대로 나온다.

<iframe src="/assets/demos/count-min-sketch.html" title="카운트-민 스케치 데모" width="100%" height="760" style="border:1px solid #e5e7eb;border-radius:12px" loading="lazy"></iframe>

---

## 4. Redis 8에서 써 보기

처음에는 쓰던 `redis:7-alpine`에서 바로 해 보려 했는데 명령 자체가 없었다.

```bash
# redis 7.4.9
127.0.0.1:6379> CMS.INITBYPROB sketch 0.001 0.01
(error) ERR unknown command 'CMS.INITBYPROB', with args beginning with: 'sketch' '0.001' '0.01'

# redis 8.10.3
127.0.0.1:6379> CMS.INITBYPROB sketch 0.001 0.01
OK
127.0.0.1:6379> CMS.INCRBY sketch 송금 3 환율 1
1) (integer) 3
2) (integer) 1
127.0.0.1:6379> CMS.INFO sketch
1) "width"
2) (integer) 2000
3) "depth"
4) (integer) 7
5) "count"
6) (integer) 4
```

카운트-민 스케치는 원래 RedisBloom 모듈에 있었다. 2025년 5월 Redis Open Source 8.0이 나오면서 쿼리 엔진, JSON, 시계열, 확률적 자료구조(Bloom, Cuckoo, Count-Min, Top-K, t-digest)가 기본 배포판에 합쳐졌다. 7.x를 쓰고 있다면 Redis Stack이나 RedisBloom 모듈을 따로 붙여야 한다.

하나 눈에 띈 건 크기다. 같은 ε = 0.001, δ = 0.01을 넣었는데 위 공식대로라면 2719×5가 나와야 한다. Redis는 2000×7을 만들었다. 결과로 보면 w = 2/ε, d = ⌈log₂(1/δ)⌉에 해당하는 상수를 쓰는 것 같다(소스까지 확인하지는 않았다). 열이 더 적고 행이 더 많다. 이 차이가 6절에서 꽤 큰 차이를 만든다.

Redis 스케치로 가장 많이 나온 키를 물어보면 이렇다.

```
실제 1,240,251  →  CMS.QUERY 1,241,280   (+1,029, 0.08%)
```

많이 나온 키는 이 정도로 잘 맞는다.

---

## 5. 오차는 어디에 쌓이나

56만 개 키 전부를 실제 값과 비교했다. ε = 0.001(2719×5) 기준이다.

![실제 횟수 구간별 평균 과대 추정량](/assets/images/count-min-sketch/abs-error-by-frequency.svg)

더해지는 오차는 실제 횟수와 상관없이 평균 860회 안팎으로 거의 같았다. 그러니 상대 오차는 드문 키일수록 커진다.

| 실제 횟수 | 키 수 | 평균 과대 추정 | 평균 상대 오차 |
|----------|------:|-------------:|-------------:|
| 1~9 | 514,750 | +859 | 59,758% |
| 10~99 | 41,394 | +860 | 4,874% |
| 100~999 | 4,648 | +865 | 467% |
| 1,000~9,999 | 571 | +875 | 46.9% |
| 10,000 이상 | 79 | +873 | 4.2% |

키의 92%를 차지하는 "1~9번 나온 키"는 평균 600배 가까이 부풀려졌다. 3번 온 IP를 물으면 860번쯤이라고 답하는 셈이다. 오차 한계 ε·N(= 9,997)은 키의 99.9998%가 지켰으니 수학적 보장은 그대로 성립했다. 보장이 "전체 N의 0.1% 이내"라는 절대 오차라서 드문 키에는 의미가 없을 뿐이다.

그래도 56만 개 키 중 실제보다 작게 센 키는 0개였다. 모든 설정에서, Redis 실측에서도 마찬가지였다.

---

## 6. 임계값 판정의 1차 필터로 쓸 수 있을까

여기서 하고 싶었던 게 이거다. "1,000회 넘으면 의심" 같은 규칙이 있다고 하자. 실제로 1,000회를 넘은 키는 650개(전체의 0.12%)다.

카운트-민 스케치는 작게 세는 일이 없으니 이런 성질이 생긴다.

```
추정값 ≤ 1,000  →  실제값도 반드시 ≤ 1,000   (다시 셀 필요 없음)
추정값 > 1,000  →  이 키만 정확히 다시 센다
```

놓치는 키 없이 정확히 세야 하는 대상만 줄일 수 있다. 문제는 얼마나 줄어드느냐다.

![1차 필터를 통과하지 못해 다시 세야 하는 키 비율](/assets/images/count-min-sketch/recount-ratio.svg)

| 설정 | 크기 | 메모리 | 다시 셀 키 | 잘못 걸린 키 | 놓친 키 |
|------|------|------:|----------:|----------:|------:|
| ε = 0.01 | 272×5 | 5KB | 100% | 560,792 | 0 |
| Redis 실측 (`INITBYPROB 0.001 0.01`) | 2000×7 | 56KB | 80.2% | 449,826 | 0 |
| ε = 0.001 | 2719×5 | 54KB | 20.4% | 113,702 | 0 |
| ε = 0.001 + 보수적 갱신 | 2719×5 | 54KB | **0.16%** | 231 | 0 |
| ε = 0.0001 | 27183×5 | 544KB | 0.12% | 22 | 0 |

처음에는 54KB면 충분하다고 생각했다. 공식상 오차가 전체의 0.1%라니까. 그런데 N이 1천만이면 0.1%는 1만이다. 임계값 1,000보다 열 배 크다. 평균 오차만 860이니 원래 몇 번 안 온 키도 상당수가 1,000을 넘겨 버린다. 결국 다시 세야 하는 키가 20%, Redis 기본 크기로는 80%였다.

여기서 배운 건 두 가지다.

**첫째, ε·N이 임계값보다 한참 작아야 한다.** ε = 0.0001(544KB)로 늘려 ε·N을 1,000 근처까지 내리자 잘못 걸린 키가 22개로 줄었다. 그래도 HASH의 65분의 1이다.

**둘째, 같은 메모리면 보수적 갱신이 효과가 컸다.** 갱신할 때 모든 칸에 더하지 않고 현재 추정값보다 작은 칸만 올리는 방식이다. 같은 54KB에서 잘못 걸린 키가 113,702개에서 231개로 줄었다. 대신 빼기와 스케치끼리 더하기(병합)를 할 수 없게 된다. 서버 여러 대의 스케치를 합쳐야 한다면 쓸 수 없다.

덤으로 Redis 크기(2000×7)가 공식 크기(2719×5)보다 메모리는 비슷한데 결과는 훨씬 나빴다. 행을 늘리면 "보장이 깨질 확률"이 줄고 열을 늘리면 "오차 크기"가 준다. 이번처럼 오차 크기가 문제일 때는 열이 더 중요했다. Redis를 쓸 거라면 `INITBYPROB`에 맡기지 말고 `CMS.INITBYDIM`으로 열 수를 직접 정하는 편이 낫겠다.

---

## 7. 그럼 송금 FDS에는 쓸 수 있을까

송금 서비스에는 이상거래탐지(FDS)와 자금세탁방지(AML) 모니터링이 있다. "N분 안에 K번 넘게 송금하면" 같은 속도 규칙이 바로 6절의 임계값 판정이다. 그래서 FDS에 카운트-민을 쓰는 곳이 있는지 찾아봤다.

공개 자료에서 차단이나 의심거래 판정에 카운트-민을 쓴다는 운영 사례는 찾지 못했다. 스트리밍 DB로 AML 모니터링을 구현하는 예시를 봐도 분할 송금 탐지를 24시간 창의 정확한 `COUNT(*)`로 만든다. 연구 쪽에는 있다. MIDAS(AAAI 2020)는 그래프 간선 스트림에서 갑자기 몰리는 패턴을 카운트-민 스케치와 카이제곱 검정으로 잡는다. 논문은 침입 탐지와 사기 탐지를 적용 분야로 든다.

판정에 쓰지 않는 이유는 실험 결과를 보면 짐작이 간다.

- 근거를 다시 보여 줘야 한다. 차단이나 보고를 했다면 나중에 "왜 걸렸나"를 설명해야 한다. 해시 충돌로 부풀려진 숫자는 설명이 안 된다.
- 잘못 걸린 키 하나가 사람의 검토 한 건이다. 6절 표의 "잘못 걸린 키"가 전부 담당자 업무가 된다.
- 계정 단위 키는 정확히 세도 감당이 된다. 계정이나 카드 수는 IP처럼 끝없이 늘지 않는다.
- 공격자가 충돌을 일부러 만들 여지가 있다. 해시를 알면 특정 키의 추정값을 부풀릴 수 있다. 해시에 비밀 시드(위 코드의 `salt`)를 넣는 이유다.

그래서 이렇게 나눴다.

| 쓸 수 있는 곳 | 쓰면 안 되는 곳 |
|-------------|---------------|
| 정확히 다시 세기 전의 1차 필터 | 차단·보류의 최종 근거 |
| IP·기기처럼 키가 끝없이 느는 인프라 방어 (로그인 시도 폭주 등) | 의심거래 보고의 근거 수치 |
| 근사값을 감수할 수 있는 ML 모델 입력 피처 | 한도·누적 금액 계산 |
| 송금 그래프에서 몰리는 패턴의 후보 찾기 | "처음 보는 수취인인가" 같은 판단 |

마지막 줄은 과대 추정 때문이다. 한 번도 안 본 수취인을 "본 적 있다"고 답하기도 한다. 처음 보는 거래를 놓치는 방향의 오류라 위험하다.

---

## 8. 다른 회사는 어떻게 썼나

찾아본 글 중 실제로 쓴 이야기가 있는 것만 추렸다.

- **Report URI**: 누적 리포트 6,691억 건에서 "이 리포트가 전에 몇 번 왔나"를 DB로 정확히 묻기 어려워 카운트-민으로 바꿨다. Counting Bloom Filter와 비교한 과정이 있어서 가장 읽을 만했다.
- **Apple**: iOS 이모지 사용 통계를 모을 때 기기마다 노이즈를 섞은 벡터를 보내고 서버가 행렬에 더한다. 조회할 때 최솟값 대신 평균을 써서 Count Mean Sketch라고 부른다. 병합이 덧셈 한 번이라는 성질을 잘 쓴 예다.
- **Caffeine, Ristretto**: Java와 Go의 캐시 라이브러리다. 새 항목을 캐시에 넣을지 정할 때(TinyLFU) 4비트 카운트-민으로 접근 빈도를 추정하고 주기적으로 절반으로 줄인다. Spring에서 크기 제한을 건 Caffeine 캐시를 쓰고 있다면 이미 카운트-민을 쓰고 있는 셈이다.
- **Cloudflare**: 오픈소스 프록시 Pingora의 요청 제한 모듈(`pingora-limits`)에 락 없는 카운트-민 추정기가 들어 있다. 6절에서 말한 "인프라 방어" 쪽 쓰임이다.

어느 쪽도 최종 판정에 쓰지 않는다. 모두 "대략 많은가"를 묻는 자리다.

---

## 9. 회고

처음에는 "54KB로 1천만 건을 센다"는 숫자에 끌렸다. 공식상 오차 0.1%면 충분해 보였다. 그런데 막상 임계값 하나를 걸어 보니 키의 20%를 다시 세야 했다. ε·N이라는 숫자가 실제로 얼마인지 계산해 보기 전까지는 몰랐다.

랭킹 글에서 "실시간으로 풀 수 있다와 풀어야 한다는 다르다"고 썼다. 이번에도 비슷했다. 대략 셀 수 있다는 것과 대략 세도 된다는 건 다른 질문이었다. 두 번째 질문의 답은 "무엇을 결정하는 숫자인가"에 달려 있었다. 사람을 막는 숫자라면 정확해야 하고, 다시 셀 대상을 고르는 숫자라면 대략이어도 된다.

---

## 10. 남은 과제

- **시간 창**: 이번 실험은 하나의 창에서 전체를 셌다. 실제 규칙은 "최근 10분" 같은 슬라이딩 윈도우라서 1분짜리 스케치를 여러 개 두고 돌리는 방식을 재 봐야 한다.
- **여러 서버 병합**: 보수적 갱신은 병합이 안 된다. 서버마다 스케치를 두고 합치는 구조와 Redis 하나에 모으는 구조를 비교해 봐야 한다.
- **Top-K**: Redis의 `TOPK.*`는 카운트-민이 아니라 HeavyKeeper 알고리즘이다. "상위 100개 IP"처럼 목록이 필요하면 이쪽이 맞을 것 같아서 같은 데이터로 비교해 보려 한다.
- **공격 시나리오**: 해시 시드를 아는 경우와 모르는 경우 충돌을 얼마나 만들 수 있는지는 재 보지 않았다.

---

## 참고

- [Cormode & Muthukrishnan, An Improved Data Stream Summary: The Count-Min Sketch and its Applications](https://dsf.berkeley.edu/cs286/papers/countmin-latin2004.pdf): 원 논문
- [Redis Docs, Count-min sketch](https://redis.io/docs/latest/develop/data-types/probabilistic/count-min-sketch/): `CMS.*` 명령 문서
- [Redis Open Source 8.0 release notes](https://redis.antirez.com/commands/content/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.0-release-notes.md): 확률적 자료구조가 기본 배포판에 합쳐진 릴리스
- [Report URI, Frequency analysis of hundreds of billions of reports with a Count-Min Sketch](https://scotthelme.co.uk/frequency-analysis-hundreds-billions-reports-report-uri-count-min-sketch/): Counting Bloom Filter와 비교해 고른 과정
- [Apple, Learning with Privacy at Scale](https://machinelearning.apple.com/research/learning-with-privacy-at-scale): Count Mean Sketch와 차등 프라이버시
- [Einziger, Friedman, Manes, TinyLFU](https://ar5iv.labs.arxiv.org/html/1512.00727): Caffeine 캐시의 빈도 추정
- [Cloudflare pingora-limits estimator](https://docs.rs/crate/pingora-limits/0.8.1/source/src/estimator.rs): 요청 제한용 카운트-민 구현
- [Bhatia et al., MIDAS: Real-Time Anomaly Detection in Edge Streams](https://arxiv.org/pdf/2009.08452): 그래프 스트림 이상 탐지에 카운트-민 적용
- [RisingWave, AML Transaction Monitoring with a Streaming Database](https://risingwave.com/blog/aml-transaction-monitoring-streaming-database): 정확한 창 집계로 만든 AML 규칙 예시
