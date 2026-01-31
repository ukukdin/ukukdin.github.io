---
layout: post
title: "HTTP Session 인증 방식 이해하기"
date: 2024-01-04
category: CS Study
tags: [HTTP, Session, Authentication, Cookie]
excerpt: "웹 어플리케이션에서 사용하는 HTTP Session 인증 방식과 그 동작 과정을 알아봅니다."
---

## HTTP Session 인증이란?

HTTP Session 인증은 웹 어플리케이션에서 사용하는 인증 방법으로, 사용자 인증 정보를 서버 측에서 유지하고 관리하기 위한 방법 중 하나입니다.

## 인증 과정

1. **사용자가 로그인을 시도**
2. **서버는 사용자의 인증 정보를 검증하여 Session ID를 생성**
3. **세션은 서버 측에서 관리되며, 서버에서 갱신 및 정보를 변경할 수 있습니다**
4. **세션 ID는 쿠키(Cookies) 방식으로 사용자에게 전달되며, 웹 어플리케이션에서 사용됩니다**
