(() => {
  const PLACES = [
    { label: '강남역 2번 출구', lat: 37.4979, lng: 127.0276 },
    { label: '판교역 2번 출구', lat: 37.3947, lng: 127.1112 },
    { label: '광교중앙역 1번 출구', lat: 37.2886, lng: 127.0516 },
    { label: '수원역 환승센터', lat: 37.2660, lng: 127.0005 },
    { label: '야탑역 1번 출구', lat: 37.4114, lng: 127.1286 },
    { label: '정자역 3번 출구', lat: 37.3670, lng: 127.1081 },
    { label: '선릉역 1번 출구', lat: 37.5045, lng: 127.0489 },
    { label: '서울역 1번 출구', lat: 37.5547, lng: 126.9707 },
    { label: '의왕역 1호선 1번 출구 앞', lat: 37.3208, lng: 126.9482 }
  ];

  let csrfToken = null;
  let lastState = null;
  const autoDrivePickups = new Set();

  const $ = (id) => document.getElementById(id);

  function showBanner(msg, isError = false) {
    const el = $('banner');
    el.textContent = msg;
    el.className = 'banner' + (isError ? ' error' : '');
    clearTimeout(showBanner._t);
    showBanner._t = setTimeout(() => { el.className = 'banner hidden'; }, 5500);
  }

  async function api(url, method = 'GET', body) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && method !== 'HEAD' && csrfToken) {
      headers['X-Ops-CSRF'] = csrfToken;
    }
    const res = await fetch(url, {
      method,
      headers,
      credentials: 'same-origin',
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    if (text) {
      try { json = JSON.parse(text); } catch { json = null; }
    }
    if (res.status === 401) {
      showLogin();
      throw new Error(json?.message || '관리자 인증이 만료되었습니다.');
    }
    if (!res.ok) {
      throw new Error(json?.message || `요청 실패 (${res.status})`);
    }
    return json;
  }

  function showLogin(errMsg = '') {
    $('app-view').classList.add('hidden');
    $('login-view').classList.remove('hidden');
    const errEl = $('login-error');
    if (errMsg) {
      errEl.textContent = errMsg;
      errEl.classList.remove('hidden');
    } else {
      errEl.classList.add('hidden');
    }
  }

  function showApp(session) {
    csrfToken = session.csrfToken || null;
    $('login-view').classList.add('hidden');
    $('app-view').classList.remove('hidden');
    const logoutBtn = $('logout-btn');
    if (session.authRequired) logoutBtn.classList.remove('hidden');
    else logoutBtn.classList.add('hidden');
  }

  function populatePlaceSelect(selectEl) {
    selectEl.textContent = '';
    for (const p of PLACES) {
      const opt = document.createElement('option');
      opt.value = `${p.label}|${p.lat}|${p.lng}`;
      opt.textContent = p.label;
      selectEl.append(opt);
    }
  }

  function parseSelectedPlace(selectEl) {
    const [placeLabel, lat, lng] = (selectEl.value || '').split('|');
    return {
      placeLabel: placeLabel || '강남역 2번 출구',
      latitude: Number(lat) || 37.4979,
      longitude: Number(lng) || 127.0276,
    };
  }

  function makeBadge(text, cls) {
    const span = document.createElement('span');
    span.className = `badge ${cls}`;
    span.textContent = text;
    return span;
  }

  function statusBadgeClass(status) {
    switch (status) {
      case 'ACTIVE': return 'badge-blue';
      case 'ARRIVED': return 'badge-green';
      case 'WAITING_APPROVAL':
      case 'INVITED':
      case 'QUEUED':
      case 'PENDING': return 'badge-orange';
      case 'CANCELLED':
      case 'REJECTED':
      case 'EXPIRED': return 'badge-red';
      default: return 'badge-slate';
    }
  }

  function actionBtn(label, cls, fn) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `btn btn-sm ${cls}`;
    btn.textContent = label;
    btn.addEventListener('click', async () => {
      try {
        await fn();
        await loadState();
      } catch (e) {
        showBanner(e.message, true);
      }
    });
    return btn;
  }

  async function copyText(text, label) {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      showBanner(`${label} 복사 완료: ${text}`);
    } catch {
      showBanner(text);
    }
  }

  function fillSelectKeepingValue(selectEl, items, labelFn) {
    const prev = selectEl.value;
    selectEl.textContent = '';
    for (const m of items) {
      const opt = document.createElement('option');
      opt.value = m.memberId;
      opt.textContent = labelFn(m);
      selectEl.append(opt);
    }
    if (prev && [...selectEl.options].some((o) => o.value === prev)) {
      selectEl.value = prev;
    }
  }

  function renderHeaderStats(state) {
    const envBadge = $('env-badge');
    envBadge.textContent = state.deployment ? '운영 서버 (majung.cloud)' : '로컬 개발 서버';
    envBadge.className = `badge ${state.deployment ? 'badge-red' : 'badge-blue'}`;
    $('slot-badge').textContent = `slot: ${state.slot || 'standalone'}`;

    $('kpi-real').textContent = String(state.stats.realMembers);
    $('kpi-dummy').textContent = String(state.stats.dummyMembers);
    $('kpi-vehicles').textContent = String(state.stats.linkedVehicles);
    $('kpi-pickups').textContent = String(state.stats.openPickups);
    $('kpi-requests').textContent = String(state.stats.openRequests);
  }

  function renderRealMembers(members) {
    const container = $('real-members-grid');
    container.textContent = '';
    const realMembers = members.filter((m) => !m.dummy);
    if (!realMembers.length) {
      const empty = document.createElement('p');
      empty.className = 'muted';
      empty.textContent = '아직 카카오 로그인으로 가입된 실회원이 없습니다. iPhone 앱에서 먼저 카카오 로그인을 진행하거나 오른쪽 위 마중 ID 입력란을 사용하세요.';
      container.append(empty);
      return;
    }

    for (const m of realMembers) {
      const card = document.createElement('div');
      card.className = 'member-pill-card';

      const head = document.createElement('div');
      head.className = 'row-between';
      const title = document.createElement('strong');
      title.textContent = m.nickname;
      head.append(title, makeBadge(m.profileReady ? '프로필 완료' : '닉네임 설정 전', m.profileReady ? 'badge-green' : 'badge-orange'));

      const meta = document.createElement('div');
      meta.className = 'muted mono';
      meta.textContent = `${m.majungId}${m.driver ? ` · 차량: ${m.vehicleName}` : ''}`;

      const actions = document.createElement('div');
      actions.className = 'row-actions';
      actions.append(
        actionBtn('지인·초대·픽업 한 번에 세팅', 'btn-primary', async () => {
          await api('/api/v1/ops/api/users/inject', 'POST', { memberId: m.memberId });
          showBanner(`${m.nickname} 계정에 더미 지인, 운전자 초대, 진행 중 픽업을 세팅했습니다.`);
        }),
        actionBtn('ID 복사', 'btn-secondary', async () => {
          await copyText(m.majungId, '마중 ID');
        }),
      );

      card.append(head, meta, actions);
      container.append(card);
    }
  }

  function renderSelects(members) {
    const dummies = members.filter((m) => m.dummy);
    const dummyDrivers = dummies.filter((m) => m.driver);
    const allDrivers = members.filter((m) => m.driver);
    const realFirstTargets = [...members].sort((a, b) => Number(a.dummy) - Number(b.dummy));

    fillSelectKeepingValue($('conn-dummy-select'), dummies, (m) => `${m.nickname} (${m.majungId})`);
    fillSelectKeepingValue($('conn-target-select'), realFirstTargets, (m) => `${m.dummy ? '[더미]' : '[실회원]'} ${m.nickname} (${m.majungId})`);
    fillSelectKeepingValue($('inv-driver-select'), dummyDrivers, (m) => `${m.nickname} · ${m.vehicleName}`);
    fillSelectKeepingValue($('inv-passenger-select'), realFirstTargets, (m) => `${m.dummy ? '[더미]' : '[실회원]'} ${m.nickname} (${m.majungId})`);
    fillSelectKeepingValue($('sim-passenger-select'), dummies, (m) => `${m.nickname} (${m.majungId})`);
    fillSelectKeepingValue($('sim-driver-select'), allDrivers, (m) => `${m.dummy ? '[더미]' : '[실회원]'} ${m.nickname} · ${m.vehicleName}`);
  }

  function renderPickups(pickups) {
    const list = $('pickups-list');
    list.textContent = '';

    for (const activeId of [...autoDrivePickups]) {
      const stillActive = pickups.some((p) => p.pickupId === activeId && p.status === 'ACTIVE');
      if (!stillActive) autoDrivePickups.delete(activeId);
    }

    if (!pickups.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '진행 중이거나 최근 생성된 픽업이 없습니다.';
      list.append(p);
      return;
    }

    for (const p of pickups) {
      const row = document.createElement('div');
      row.className = 'item-row';

      const meta = document.createElement('div');
      meta.className = 'item-meta';

      const titleRow = document.createElement('div');
      titleRow.className = 'item-title-row';
      titleRow.append(makeBadge(p.status, statusBadgeClass(p.status)));
      const title = document.createElement('strong');
      title.textContent = `${p.placeLabel} · ${p.vehicleName}`;
      titleRow.append(title);
      if (!p.controllable) {
        titleRow.append(makeBadge('실차량 (조작 불가)', 'badge-slate'));
      }

      const sub1 = document.createElement('div');
      sub1.className = 'muted';
      const etaText = p.durationSeconds != null ? `${Math.max(1, Math.round(p.durationSeconds / 60))}분 (${p.durationSeconds}초)` : '-';
      const coordText = p.vehicleLatitude != null ? `${p.vehicleLatitude.toFixed(4)}, ${p.vehicleLongitude.toFixed(4)}` : '위치 대기';
      const recvText = p.readinessReceivedSequence >= p.readinessSequence && p.readinessSequence > 0 ? ' (수신확인됨)' : '';
      sub1.textContent = `탑승자: ${p.participantName || '미참여'} · 준비: ${p.readiness || '-'}${recvText} · ETA: ${etaText} · 차량좌표: ${coordText}`;
      meta.append(titleRow, sub1);

      if (p.quickMessage || p.proposalLabel) {
        const sub2 = document.createElement('div');
        sub2.className = 'muted';
        const parts = [];
        if (p.quickMessage) parts.push(`퀵메시지: "${p.quickMessage}"`);
        if (p.proposalLabel) parts.push(`장소변경 제안(${p.proposalBy || '-'}): ${p.proposalLabel}`);
        sub2.textContent = parts.join(' · ');
        meta.append(sub2);
      }

      const actions = document.createElement('div');
      actions.className = 'row-actions';
      if (p.controllable) {
        if (p.status === 'WAITING_APPROVAL') {
          actions.append(actionBtn('게스트 탑승 승인', 'btn-success', async () => {
            await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'approve-guest' });
            showBanner('게스트 탑승을 승인하고 차량 위치를 시작했습니다.');
          }));
        }
        if (p.status === 'ACTIVE') {
          const isAuto = autoDrivePickups.has(p.pickupId);
          actions.append(actionBtn(isAuto ? '자동 주행 정지' : '자동 주행 (5초)', isAuto ? 'btn-danger' : 'btn-secondary', async () => {
            if (autoDrivePickups.has(p.pickupId)) {
              autoDrivePickups.delete(p.pickupId);
              showBanner('자동 주행을 중지했습니다.');
            } else {
              autoDrivePickups.add(p.pickupId);
              showBanner('5초 간격 자동 주행을 시작했습니다.');
            }
          }));
          actions.append(actionBtn('한 걸음 접근 (-60초)', 'btn-secondary', async () => {
            await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'approach' });
            showBanner('차량을 목적지 방향으로 한 단계 이동했습니다.');
          }));
          actions.append(actionBtn('준비 수신확인', 'btn-secondary', async () => {
            await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'receive-readiness' });
            showBanner('탑승자 준비 상태 수신확인을 전송했습니다.');
          }));
          if (p.proposalLabel) {
            actions.append(actionBtn('장소 제안 수락', 'btn-success', async () => {
              await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'accept-proposal' });
              showBanner('제안된 장소로 목적지를 확정했습니다.');
            }));
          }
          actions.append(actionBtn('도착 처리', 'btn-primary', async () => {
            autoDrivePickups.delete(p.pickupId);
            await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'arrive' });
            showBanner('도착(ARRIVED) 처리 및 알림을 전송했습니다.');
          }));
        }
        if (p.status === 'ACTIVE' || p.status === 'ARRIVED') {
          actions.append(actionBtn('완료', 'btn-success', async () => {
            autoDrivePickups.delete(p.pickupId);
            await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'complete' });
            showBanner('픽업을 완료(COMPLETED) 처리했습니다.');
          }));
          actions.append(actionBtn('취소', 'btn-danger', async () => {
            autoDrivePickups.delete(p.pickupId);
            await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'cancel' });
            showBanner('픽업을 취소(CANCELLED) 처리했습니다.');
          }));
        }
        if (p.dummyPassenger && p.status === 'ACTIVE') {
          actions.append(actionBtn('더미 탑승자 "지금 내려가요"', 'btn-secondary', async () => {
            await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, readiness: 'READY', quickMessage: '지금 내려가요' });
            showBanner('더미 탑승자 준비 완료를 반영했습니다.');
          }));
        }
      }

      row.append(meta, actions);
      list.append(row);
    }
  }

  function renderRequests(requests) {
    const list = $('requests-list');
    list.textContent = '';
    const filter = $('request-filter').value;
    const visible = requests.filter((r) => filter === 'ALL' || ['PENDING', 'INVITED', 'QUEUED', 'ACTIVE'].includes(r.status));
    if (!visible.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '표시할 마중 요청이 없습니다.';
      list.append(p);
      return;
    }

    for (const r of visible) {
      const row = document.createElement('div');
      row.className = 'item-row';

      const meta = document.createElement('div');
      meta.className = 'item-meta';

      const titleRow = document.createElement('div');
      titleRow.className = 'item-title-row';
      titleRow.append(makeBadge(r.status, statusBadgeClass(r.status)));
      if (r.driverInvitation) titleRow.append(makeBadge('운전자 초대', 'badge-slate'));
      const title = document.createElement('strong');
      title.textContent = `${r.passengerName} → ${r.driverName}`;
      titleRow.append(title);

      const sub = document.createElement('div');
      sub.className = 'muted';
      const q = r.queueOrder != null ? ` · 대기순서 #${r.queueOrder}` : '';
      sub.textContent = `장소: ${r.placeLabel}${q}`;
      meta.append(titleRow, sub);

      const actions = document.createElement('div');
      actions.className = 'row-actions';
      if (r.controllable) {
        if (r.status === 'PENDING') {
          actions.append(actionBtn('운전자 수락', 'btn-primary', async () => {
            await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'accept' });
            showBanner('요청을 수락하여 대기열에 추가했습니다.');
          }));
          actions.append(actionBtn('운전자 거절', 'btn-danger', async () => {
            await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'reject' });
            showBanner('요청을 거절했습니다.');
          }));
        }
        if (r.status === 'QUEUED') {
          actions.append(actionBtn('운행 시작 (ACTIVE)', 'btn-success', async () => {
            await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'start' });
            showBanner('픽업을 시작하고 초기 차량 좌표를 전송했습니다.');
          }));
        }
        if (r.status === 'ACTIVE') {
          actions.append(actionBtn('도착', 'btn-primary', async () => {
            await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'arrive' });
            showBanner('차량을 도착 상태로 전환했습니다.');
          }));
          actions.append(actionBtn('완료', 'btn-success', async () => {
            await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'complete' });
            showBanner('픽업을 완료했습니다.');
          }));
        }
        if (r.driverInvitation && ['INVITED', 'QUEUED'].includes(r.status)) {
          actions.append(actionBtn('초대 취소', 'btn-danger', async () => {
            await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'cancel' });
            showBanner('운전자 초대를 취소했습니다.');
          }));
        }
      } else {
        actions.append(makeBadge('실차량/실운전자 (수동 제어)', 'badge-slate'));
      }

      row.append(meta, actions);
      list.append(row);
    }
  }

  function renderLinks(state) {
    const inviteEl = $('guest-invite-url');
    const inviteAnchor = $('open-invite-link');
    if (state.guestInvite) {
      inviteEl.textContent = state.guestInvite.link;
      inviteAnchor.href = `/#join/${state.guestInvite.pickupId}/${state.guestInvite.inviteToken}`;
      inviteAnchor.classList.remove('hidden');
    } else {
      inviteEl.textContent = '유효한 게스트 초대 없음 (새 초대 만들기 클릭)';
      inviteAnchor.classList.add('hidden');
    }

    const qrEl = $('vehicle-qr-url');
    const qrMeta = $('vehicle-qr-meta');
    const confirmBtn = $('confirm-qr-btn');
    if (state.vehicleQr) {
      qrEl.textContent = state.vehicleQr.link;
      qrMeta.textContent = `${state.vehicleQr.vehicleName} · 요청자: ${state.vehicleQr.claimantMajungId || '아직 스캔 전 (iPhone 앱에서 입력 후 승인)'}`;
      confirmBtn.disabled = !state.vehicleQr.claimantMajungId;
    } else {
      qrEl.textContent = 'QR 미발급 (QR 발급 버튼 클릭)';
      qrMeta.textContent = '';
      confirmBtn.disabled = true;
    }
  }

  function renderDirectory(members) {
    const list = $('members-directory');
    list.textContent = '';
    const q = ($('member-search-input').value || '').trim().toLowerCase();
    const role = $('member-role-filter').value;

    const filtered = members.filter((m) => {
      if (role === 'REAL' && m.dummy) return false;
      if (role === 'DRIVER' && (!m.dummy || !m.driver)) return false;
      if (role === 'PASSENGER' && (!m.dummy || m.driver)) return false;
      if (q && !m.nickname.toLowerCase().includes(q) && !m.majungId.toLowerCase().includes(q)
          && !(m.vehicleName || '').toLowerCase().includes(q)) {
        return false;
      }
      return true;
    });

    for (const m of filtered) {
      const row = document.createElement('div');
      row.className = 'item-row';

      const meta = document.createElement('div');
      meta.className = 'item-meta';

      const titleRow = document.createElement('div');
      titleRow.className = 'item-title-row';
      titleRow.append(makeBadge(m.dummy ? '더미' : '실회원', m.dummy ? 'badge-slate' : 'badge-blue'));
      if (m.driver) titleRow.append(makeBadge('운전자', 'badge-green'));
      const strong = document.createElement('strong');
      strong.textContent = `${m.nickname} (${m.majungId})`;
      titleRow.append(strong);

      const sub = document.createElement('div');
      sub.className = 'muted';
      sub.textContent = m.vehicleName || '차량 미연결 탑승자';
      meta.append(titleRow, sub);

      const actions = document.createElement('div');
      actions.className = 'row-actions';
      actions.append(actionBtn('ID 복사', 'btn-secondary', async () => copyText(m.majungId, '마중 ID')));
      actions.append(actionBtn('시나리오 주입', 'btn-primary', async () => {
        await api('/api/v1/ops/api/users/inject', 'POST', { memberId: m.memberId });
        showBanner(`${m.nickname} 계정에 테스트 시나리오를 주입했습니다.`);
      }));

      row.append(meta, actions);
      list.append(row);
    }
  }

  function renderAll(state) {
    lastState = state;
    renderHeaderStats(state);
    renderRealMembers(state.members);
    renderSelects(state.members);
    renderPickups(state.pickups);
    renderRequests(state.requests);
    renderLinks(state);
    renderDirectory(state.members);
  }

  async function loadState() {
    const state = await api('/api/v1/ops/api/state');
    renderAll(state);
  }

  async function boot() {
    populatePlaceSelect($('inv-place-select'));
    populatePlaceSelect($('sim-place-select'));

    const session = await api('/api/v1/ops/api/session');
    if (session.authenticated) {
      showApp(session);
      await loadState();
    } else {
      showLogin();
    }
  }

  // Event bindings
  $('login-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const key = $('admin-key-input').value.trim();
    if (!key) return;
    try {
      const session = await api('/api/v1/ops/api/login', 'POST', { adminKey: key });
      $('admin-key-input').value = '';
      showApp(session);
      await loadState();
      showBanner('관리자 인증을 완료했습니다.');
    } catch (e) {
      showLogin(e.message);
    }
  });

  $('logout-btn').addEventListener('click', async () => {
    try {
      await api('/api/v1/ops/api/logout', 'POST');
    } finally {
      csrfToken = null;
      autoDrivePickups.clear();
      showLogin();
    }
  });

  $('refresh-btn').addEventListener('click', () => loadState().catch((e) => showBanner(e.message, true)));

  $('reset-btn').addEventListener('click', async () => {
    try {
      const withPickups = !lastState?.deployment;
      renderAll(await api('/api/v1/ops/api/dummy/reset', 'POST', { withSamplePickups: withPickups }));
      showBanner('모든 더미 데이터를 초기 상태로 되돌렸습니다. (실가입 회원 정보는 유지)');
    } catch (e) { showBanner(e.message, true); }
  });

  $('seed-samples-btn').addEventListener('click', async () => {
    try {
      renderAll(await api('/api/v1/ops/api/dummy/seed', 'POST', { withSamplePickups: true }));
      showBanner('샘플 진행 중 픽업과 대기 요청을 생성했습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('inject-query-btn').addEventListener('click', async () => {
    const query = $('inject-query').value.trim();
    if (!query) return showBanner('마중 ID 또는 닉네임을 입력해주세요.', true);
    try {
      renderAll(await api('/api/v1/ops/api/users/inject', 'POST', { query }));
      showBanner(`"${query}" 계정에 더미 지인·초대·진행 중 픽업을 주입했습니다.`);
    } catch (e) { showBanner(e.message, true); }
  });

  $('conn-accept-btn').addEventListener('click', async () => {
    try {
      renderAll(await api('/api/v1/ops/api/users/connect', 'POST', {
        dummyId: $('conn-dummy-select').value,
        targetId: $('conn-target-select').value,
        accept: true,
      }));
      showBanner('지인 연결을 즉시 완료했습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('conn-pending-btn').addEventListener('click', async () => {
    try {
      renderAll(await api('/api/v1/ops/api/users/connect', 'POST', {
        dummyId: $('conn-dummy-select').value,
        targetId: $('conn-target-select').value,
        accept: false,
      }));
      showBanner('수락 대기 지인 요청을 보냈습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('inv-send-btn').addEventListener('click', async () => {
    try {
      const place = parseSelectedPlace($('inv-place-select'));
      await api('/api/v1/ops/api/users/invite', 'POST', {
        driverId: $('inv-driver-select').value,
        passengerId: $('inv-passenger-select').value,
        ...place,
      });
      await loadState();
      showBanner('운전자 초대를 발송했습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('sim-request-btn').addEventListener('click', async () => {
    try {
      const place = parseSelectedPlace($('sim-place-select'));
      await api('/api/v1/ops/api/requests/submit', 'POST', {
        passengerId: $('sim-passenger-select').value,
        driverId: $('sim-driver-select').value,
        ...place,
      });
      await loadState();
      showBanner('더미 탑승자 명의로 마중 요청을 보냈습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('new-invite-btn').addEventListener('click', async () => {
    try {
      await api('/api/v1/ops/api/guest-invite', 'POST', {});
      await loadState();
      showBanner('새 게스트 초대 링크를 생성했습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('copy-invite-btn').addEventListener('click', () => copyText(lastState?.guestInvite?.link, '게스트 초대 링크'));
  $('copy-qr-btn').addEventListener('click', () => copyText(lastState?.vehicleQr?.link, '차량 QR 링크'));

  $('new-qr-btn').addEventListener('click', async () => {
    try {
      await api('/api/v1/ops/api/vehicle-qr', 'POST', {});
      await loadState();
      showBanner('새 차량 연결 QR을 발급했습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('confirm-qr-btn').addEventListener('click', async () => {
    if (!lastState?.vehicleQr) return;
    try {
      await api('/api/v1/ops/api/vehicle-qr/confirm', 'POST', { challengeId: lastState.vehicleQr.challengeId });
      await loadState();
      showBanner('차량 화면 연결 승인을 완료했습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('issue-reg-code-btn').addEventListener('click', async () => {
    try {
      const issued = await api('/api/v1/ops/api/vehicle-registration-codes', 'POST', {});
      $('reg-code-output').textContent = `코드: ${issued.code} (만료: ${issued.expiresAt})`;
      await copyText(issued.code, '실차량 등록 코드');
    } catch (e) { showBanner(e.message, true); }
  });

  $('create-dummy-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    try {
      const created = await api('/api/v1/ops/api/dummy/create', 'POST', {
        nickname: $('new-dummy-nickname').value.trim(),
        vehicleName: $('new-dummy-vehicle').value.trim(),
      });
      $('new-dummy-nickname').value = '';
      $('new-dummy-vehicle').value = '';
      await loadState();
      showBanner(`새 더미 계정 "${created.nickname} (${created.majungId})"을 추가했습니다.`);
    } catch (e) { showBanner(e.message, true); }
  });

  $('request-filter').addEventListener('change', () => lastState && renderRequests(lastState.requests));
  $('member-search-input').addEventListener('input', () => lastState && renderDirectory(lastState.members));
  $('member-role-filter').addEventListener('change', () => lastState && renderDirectory(lastState.members));

  setInterval(async () => {
    if ($('app-view').classList.contains('hidden')) return;
    try {
      for (const pid of [...autoDrivePickups]) {
        await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: pid, action: 'approach' });
      }
      if ($('auto-refresh-toggle').checked || autoDrivePickups.size > 0) {
        await loadState();
      }
    } catch {
      // Ignore background poll error cleanly.
    }
  }, 5000);

  boot().catch((e) => showBanner(e.message, true));
})();
