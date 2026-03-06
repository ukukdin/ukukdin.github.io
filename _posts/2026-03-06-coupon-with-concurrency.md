---
layout: post
title: "내가 짠 코드가 무신사 타임딜을 버틸 수 있을까?"
date: 2026-03-05
category: etc
tags: [coupon, concurrency, DB lock]
excerpt: "쿠폰을 적용하며 트랜잭션과 동시성 테스트의 정합성"
---
## 들어가며

무신사 타임딜이 열리면 수만 명이 동시에 쿠폰을 누른다. "선착순 100명 30% 할인 쿠폰" 같은 걸 본 적 있을 것이다. 그 순간 서버에는 어떤 일이 벌어질까?

나는 최근 사이드 프로젝트에서 쿠폰 발급 시스템을 직접 구현했다. 선착순 발급, 1인당 한도 제한, 주문 시 쿠폰 사용까지. 코드를 다 짜고 나서 문득 궁금해졌다. **이 코드가 실제 트래픽을 맞닥뜨리면 어디까지 버틸 수 있을까?** 그리고 버티지 못한다면, 어디서부터 무너질까?

이 글은 내 코드를 솔직하게 들여다보면서, 대용량 트래픽 앞에서 어떤 선택이 유효하고 어떤 한계가 있는지를 정리한 기록이다.

---

## 내 쿠폰 시스템은 이렇게 생겼다

먼저 구조부터 간단히 짚고 가자.

쿠폰 도메인은 크게 두 개의 Aggregate로 나뉜다.

- **Coupon**: 쿠폰 자체의 정보. 할인 정책, 발급 정책, 유효기간 등을 가진다.
- **UserCoupon**: 사용자에게 발급된 쿠폰. AVAILABLE, USED, EXPIRED 같은 상태를 관리한다.

왜 분리했냐면, 생명주기가 다르기 때문이다. 쿠폰은 관리자가 만들고, UserCoupon은 사용자가 발급받을 때 생긴다. 하나의 Coupon에 수천 개의 UserCoupon이 달릴 수 있는데, 이걸 하나의 Aggregate로 묶으면 쿠폰 하나 조회할 때마다 발급 이력 전체를 끌고 와야 한다. 그건 말이 안 된다.

발급 흐름은 이렇다:

```
POST /api/v1/coupons/{couponId}/issue

1. 쿠폰 조회 (비관적 락)
2. 발급 가능 여부 검증 (상태, 기간, 재고)
3. 1인당 발급 한도 확인
4. 발급 카운트 증가 → 쿠폰 저장
5. UserCoupon 생성 → 저장
```

이 다섯 단계가 하나의 트랜잭션 안에서 돌아간다. 전부 성공하거나 전부 실패하거나. 중간에 잘못되면 롤백된다.

---

## 동시성 제어: 왜 비관적 락을 골랐나

선착순 쿠폰에서 가장 중요한 건 뭘까? **100장 한정인데 101장이 나가면 안 된다는 것이다.**

동시에 1,000명이 발급 버튼을 누르면, 서버에는 1,000개의 요청이 거의 동시에 들어온다. 이때 쿠폰의 `issuedCount`를 안전하게 다루지 않으면, 100장 한정 쿠폰이 300장 나가는 사태가 벌어진다.

내가 선택한 방법은 **비관적 락(Pessimistic Lock)**이다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM CouponJpaEntity c WHERE c.id = :id")
Optional<CouponJpaEntity> findByIdForUpdate(@Param("id") Long id);
```

이 쿼리가 실행되면 DB에서 해당 row에 `SELECT ... FOR UPDATE`가 걸린다. 다른 트랜잭션이 같은 row를 읽으려 하면, 앞의 트랜잭션이 끝날 때까지 대기한다.

### 왜 낙관적 락은 안 썼을까?

낙관적 락(`@Version`)도 고려했다. 하지만 내 도메인 모델은 **불변 객체** 패턴을 쓰고 있다. 상태가 바뀌면 기존 객체를 수정하는 게 아니라 새 객체를 만들어 반환한다.

```java
public Coupon issue() {
    IssuancePolicy incremented = this.issuancePolicy.incrementIssuedCount();
    return new Coupon(
        this.id, this.code, this.name, this.description,
        this.discountPolicy, incremented, this.expiredAt,
        this.applicationTarget, this.isDuplicate,
        this.status, this.createdAt, LocalDateTime.now()
    );
}
```

문제는 여기서 생긴다. JPA의 `@Version`은 엔티티의 동일한 인스턴스가 유지되어야 동작한다. 도메인 객체를 새로 만들어서 JPA 엔티티로 변환하면, version 필드가 null이 되거나 의도와 다르게 동작할 수 있다. 불변 모델과 낙관적 락은 궁합이 잘 안 맞았다.

비관적 락은 단순하다. "내가 먼저 잡았으니 끝날 때까지 기다려." DB가 알아서 직렬화해준다. 구현이 간결하고, 선착순 시나리오에서는 의미도 맞다. 먼저 온 사람이 먼저 받는 거니까.

---

## 그래서, 이 코드가 트래픽을 얼마나 버틸 수 있을까?

솔직하게 말하면, **단일 DB + 비관적 락 구조는 동시 사용자가 많아질수록 성능이 선형적으로 떨어진다.**

왜 그런지 구조적으로 살펴보자.

### 병목 지점 1: 락 대기 시간

비관적 락은 한 번에 하나의 트랜잭션만 해당 row를 수정할 수 있게 한다. 1,000명이 동시에 같은 쿠폰을 발급받으려 하면, 999명은 대기열에 선다.

한 트랜잭션이 쿠폰 발급에 걸리는 시간을 추정해보자:

| 단계 | 예상 소요 시간 |
|------|---------------|
| SELECT ... FOR UPDATE (쿠폰 조회 + 락) | ~2-5ms |
| COUNT 쿼리 (1인당 한도 확인) | ~1-3ms |
| UPDATE (issuedCount 증가) | ~1-2ms |
| INSERT (UserCoupon 생성) | ~1-2ms |
| 커밋 | ~1-2ms |

대략 한 트랜잭션에 **5~15ms** 정도. 이게 직렬로 처리되니까, 1,000명이 대기하면 마지막 사람은 최대 **5~15초**를 기다릴 수 있다.

무신사 타임딜에 10,000명이 동시 접속한다면? 단순 계산으로 50~150초. **이건 타임아웃이 나기 전에 DB 커넥션 풀이 먼저 바닥난다.**

### 병목 지점 2: DB 커넥션 풀 고갈

Spring Boot의 기본 HikariCP 커넥션 풀 크기는 10개다. 보통 실 서비스에서는 30~50개 정도로 설정한다.

비관적 락으로 대기 중인 트랜잭션은 DB 커넥션을 물고 있다. 50개 커넥션이 전부 락 대기에 걸리면, 쿠폰뿐 아니라 **상품 조회, 주문, 결제 등 서비스 전체가 먹통이 된다.** 쿠폰 하나 때문에 전체 서비스가 장애 나는 거다. 이게 실제로 대형 커머스에서 자주 발생하는 패턴이다.

### 병목 지점 3: 1인당 한도 확인 쿼리

```java
int userIssuedCount = userCouponRepository
    .countByUserIdAndCouponId(userId, couponId);
```

이 COUNT 쿼리가 비관적 락 안에서 실행된다. 즉, 락을 잡은 상태에서 추가 쿼리를 하나 더 날리는 것이다. user_coupons 테이블에 적절한 인덱스가 없으면, 이 쿼리 하나가 트랜잭션 시간을 크게 늘린다. 락을 오래 들고 있을수록 뒤에서 기다리는 사람들의 대기 시간이 배로 늘어난다.

---

## 현실적으로 어느 정도까지 괜찮을까?

과장 없이, 수치로 따져보자.

**트랜잭션 하나에 10ms**가 걸린다고 가정하면:

- **동시 100명**: 마지막 사람 대기시간 ~1초. 사용자 경험에 큰 문제 없다. 괜찮다.
- **동시 500명**: 마지막 사람 대기시간 ~5초. 좀 느리지만, 선착순이라 어느 정도 수용 가능하다.
- **동시 1,000명**: 마지막 사람 대기시간 ~10초. 타임아웃 경계. 커넥션 풀 고갈 위험 시작.
- **동시 5,000명 이상**: 커넥션 풀 고갈, DB 부하, 서비스 전체 장애 가능성 높다.

**내 코드가 안정적으로 처리할 수 있는 범위는 동시 수백 명 수준**이다.

소규모 커머스, 사내 이벤트, 초기 스타트업 정도의 규모라면 이 구조로 충분하다. 하지만 무신사 타임딜처럼 수만 명이 동시에 몰리는 상황이라면? 솔직히 이 코드만으로는 버티기 어렵다.

---

## 무신사 급 트래픽을 버티려면 어떤 방향으로 가야 할까?

내 코드를 기반으로, 트래픽 규모에 따라 어떤 개선이 필요한지 단계별로 정리해봤다.

### 1단계: DB 최적화 (동시 ~1,000명)

가장 먼저 할 수 있는 건 락을 잡고 있는 시간을 줄이는 것이다.

**인덱스 추가:**
```sql
CREATE INDEX idx_user_coupons_user_coupon
ON user_coupons(user_id, coupon_id);
```

1인당 한도 확인 COUNT 쿼리가 빨라진다. 이것만으로 트랜잭션 시간이 줄고, 전체 처리량이 올라간다.

**커넥션 풀 분리:**

쿠폰 발급 전용 DataSource를 따로 두면, 쿠폰 트래픽이 몰려도 일반 서비스에 영향을 주지 않는다. 실제로 대형 커머스에서 많이 쓰는 패턴이다.

### 2단계: Redis로 재고 관리 분리 (동시 ~10,000명)

핵심 아이디어는 간단하다. **재고 확인과 차감을 DB가 아니라 Redis에서 하는 것이다.**

```
Redis: DECR coupon:{couponId}:stock
→ 결과가 0 이상이면 발급 진행
→ 0 미만이면 즉시 "품절" 응답
```

Redis의 DECR 연산은 **싱글 스레드로 원자적으로 실행**된다. 락이 필요 없다. 초당 수만 건의 요청을 처리할 수 있다.

이 구조에서 DB 비관적 락은 사라진다. Redis가 재고의 1차 방어선이 되고, DB는 발급 이력 저장용으로만 쓴다.

실제로 많은 대형 커머스가 이 방식을 쓴다. 쿠팡의 로켓 쿠폰, 배달의민족의 할인 이벤트 등에서도 비슷한 패턴이 적용된다고 알려져 있다.

### 3단계: 메시지 큐로 비동기 처리 (동시 ~100,000명)

Redis로 재고를 확인한 뒤, 실제 발급 처리는 Kafka 같은 메시지 큐에 넣어 비동기로 처리하는 방식이다.

```
요청 → Redis 재고 차감 → "발급 예정" 즉시 응답
                      → Kafka 발행
                      → Consumer가 실제 DB 저장
```

사용자는 "쿠폰이 발급 처리 중입니다"라는 응답을 즉시 받고, 실제 DB 저장은 백그라운드에서 진행된다. 피크 트래픽을 큐가 흡수하기 때문에, DB에 가해지는 순간 부하를 크게 줄일 수 있다.

다만, 이 구조는 **최종 일관성(Eventual Consistency)**을 전제로 한다. 사용자가 쿠폰을 받았는데 DB에는 아직 안 들어간 짧은 시간 차이가 생길 수 있다. 이걸 허용할 수 있는지는 비즈니스 요구사항에 달려 있다.

---

## 내가 실제로 맞닥뜨린 동시성 버그

이론만 얘기하면 재미없으니, 개발하면서 직접 겪은 동시성 버그 하나를 공유한다.

좋아요 기능을 구현하고 동시성 테스트를 돌렸더니, 10명이 동시에 좋아요를 누르면 `likeCount`가 10이 아니라 2가 되는 현상이 발생했다. 8개의 좋아요가 증발한 것이다.

원인은 **조회와 갱신 사이의 이격**, 흔히 말하는 Lost Update 문제였다.

당시 코드를 보자. 좋아요 흐름은 `LikeService`와 `LikeEventHandler` 두 곳에 걸쳐 있었다.

```java
// LikeService.java (수정 전)
public void like(UserId userId, Long productId) {
    findProduct(productId);  // ← 락 없이 상품 존재 확인만
    // ...
    likeRepository.save(like);
    domainEventPublisher.publishEvents(like);
}

private Product findProduct(Long productId) {
    return productRepository.findActiveById(productId)  // 락 없음
            .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
}
```

```java
// LikeEventHandler.java (수정 전)
@EventListener
public void handle(ProductLikedEvent event) {
    Product product = productRepository.findById(event.productId())  // 락 없음
            .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
    Product updated = product.increaseLikeCount();
    productRepository.save(updated);
}
```

문제가 보이는가? **어디에도 락이 없다.** `LikeService`에서 상품을 조회할 때도, `LikeEventHandler`에서 `likeCount`를 증가시킬 때도. 그래서 10개 스레드가 이런 일을 동시에 벌인다:

```
Thread A: findById(1) → likeCount=0       Thread F: findById(1) → likeCount=0
Thread B: findById(1) → likeCount=0       Thread G: findById(1) → likeCount=0
Thread C: findById(1) → likeCount=0       Thread H: findById(1) → likeCount=0
Thread D: findById(1) → likeCount=0       Thread I: findById(1) → likeCount=0
Thread E: findById(1) → likeCount=0       Thread J: findById(1) → likeCount=0

→ 10개 스레드 모두 likeCount=0을 읽음
→ 각자 likeCount=1로 저장
→ 나중에 커밋된 몇 개만 살아남음
→ 최종 결과: likeCount=2 (기대값: 10)
```

모두가 같은 시점의 `likeCount=0`을 읽고, 각자 1을 더해서 저장한다. 먼저 저장한 값을 뒤에 오는 스레드가 덮어쓴다. 이게 **갱신 손실(Lost Update)**이다. 조회 시점과 갱신 시점 사이에 다른 스레드가 끼어들 수 있는 틈이 있기 때문에 발생하는, 동시성 환경에서의 고전적인 문제다.

해결은 **조회와 갱신을 하나의 임계 영역으로 묶는 것**, 즉 비관적 락을 거는 것이었다.

```java
// LikeService.java (수정 후)
private Product findProductWithLock(Long productId) {
    return productRepository.findActiveByIdWithLock(productId)  // 비관적 락 추가
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
}
```

```java
// LikeEventHandler.java (수정 후)
@EventListener
public void handle(ProductLikedEvent event) {
    Product product = productRepository.findActiveByIdWithLock(event.productId())  // 비관적 락 추가
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
    Product updated = product.increaseLikeCount();
    productRepository.save(updated);
}
```

`LikeService`에서 처음 조회할 때 `FOR UPDATE`로 락을 잡으면, 같은 트랜잭션 안에서 동작하는 `LikeEventHandler`까지 그 락의 보호를 받는다. 다른 스레드는 이 트랜잭션이 끝날 때까지 같은 상품 row에 접근할 수 없다.

```
Thread A: findActiveByIdWithLock(1) → 락 획득, likeCount=0 → +1 → save(1) → 커밋, 락 해제
Thread B: findActiveByIdWithLock(1) → 락 대기... → 획득, likeCount=1 → +1 → save(2) → 커밋
Thread C: findActiveByIdWithLock(1) → 락 대기... → 획득, likeCount=2 → +1 → save(3) → 커밋
...
결과: likeCount=10 ✓
```

이 경험에서 배운 건 하나다. **"조회만 하는 거니까 락은 필요 없겠지"라는 생각이 위험하다는 것.** 그 조회 결과를 기반으로 갱신이 일어난다면, 조회 시점부터 락으로 보호해야 한다. 쿠폰 발급 코드에서 처음부터 비관적 락을 건 것도 이 경험이 있었기 때문이다.

---

## 정리하면서: 코드의 가치는 한계를 아는 데 있다

내 쿠폰 발급 코드를 냉정하게 평가하면 이렇다.

**잘한 점:**
- 비관적 락으로 재고 정합성을 확실하게 보장한다. 100장 한정이면 100장만 나간다.
- 불변 도메인 모델로 코드 추론이 쉽다. 어디서 상태가 바뀌는지 명확하다.
- Aggregate 분리로 도메인 설계가 깔끔하다. 쿠폰과 발급 이력의 생명주기를 독립적으로 관리한다.
- 단일 트랜잭션으로 원자성을 보장한다. 중간에 실패해도 데이터가 꼬이지 않는다.

**한계:**
- 단일 DB 비관적 락은 동시 수백 명까지는 괜찮지만, 수천 명 이상에서는 병목이 된다.
- 커넥션 풀 고갈로 쿠폰 외 서비스까지 영향을 줄 수 있다.
- 수평 확장(서버 여러 대)에서는 DB 락만으로 한계가 있다.

이걸 부끄러워할 필요는 없다고 생각한다.

모든 시스템은 **현재 규모에 맞는 적정 설계**가 있다. 일 주문 100건인 서비스에 Kafka와 Redis를 붙이는 건 오버엔지니어링이다. 반대로 일 주문 10만 건인 서비스에 단일 DB 비관적 락만 쓰는 건 사고를 기다리는 것이다.

중요한 건 **내 코드가 어디까지 버틸 수 있는지 아는 것**, 그리고 **트래픽이 늘어났을 때 어떤 방향으로 개선해야 하는지 미리 그림을 그려두는 것**이다.

내 코드가 무신사 타임딜을 버틸 수 있냐고? 지금은 아니다. 하지만 어디를 고치면 버틸 수 있는지는 안다. 그게 이 코드의 진짜 가치라고 생각한다.

---

### 참고 자료

- [MySQL InnoDB Locking](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html) - SELECT ... FOR UPDATE의 동작 방식
- [HikariCP Connection Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) - 커넥션 풀 크기 산정 가이드
- [Redis DECR Command](https://redis.io/commands/decr/) - 원자적 감소 연산
- 우아한형제들 기술 블로그 - 선착순 이벤트 서버 생존기
- 토스 기술 블로그 - 동시성 문제와 분산 락
