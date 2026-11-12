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

  const TARGET_STORAGE_KEY = 'majung.ops.selectedTargetId';
  const TAB_STORAGE_KEY = 'majung.ops.selectedTab';
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

  function switchTab(name) {
    const targetName = ['passenger', 'driver', 'manage'].includes(name) ? name : 'passenger';
    sessionStorage.setItem(TAB_STORAGE_KEY, targetName);
    document.querySelectorAll('.tab-btn').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.tab === targetName);
    });
    document.querySelectorAll('.tab-panel').forEach((panel) => {
      panel.classList.toggle('hidden', panel.id !== `tab-${targetName}`);
    });
  }

  function populatePlaceSelect(selectEl) {
    const prev = selectEl.value;
    selectEl.textContent = '';
    for (const p of PLACES) {
      const opt = document.createElement('option');
      opt.value = `${p.label}|${p.lat}|${p.lng}`;
      opt.textContent = p.label;
      selectEl.append(opt);
    }
    if (prev && [...selectEl.options].some((o) => o.value === prev)) {
      selectEl.value = prev;
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
      showBanner(`${label} 복사 완료`);
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

  function selectedMember(state) {
    const selectedId = $('active-target-select').value;
    return state?.members?.find((x) => x.memberId === selectedId) || null;
  }

  function updateTargetVehicleStatus(state) {
    const pill = $('target-vehicle-pill');
    const driverBanner = $('driver-mode-status');
    const m = selectedMember(state);
    if (!m) {
      pill.textContent = '계정 미선택';
      pill.className = 'badge badge-slate';
      driverBanner.textContent = '상단에서 테스트할 계정을 선택해주세요.';
      driverBanner.className = 'role-notice compact-gap';
      return;
    }
    if (m.driver) {
      pill.textContent = `연결 차량: ${m.vehicleName || '등록됨'}`;
      pill.className = 'badge badge-green';
      driverBanner.textContent = `연결 차량: ${m.vehicleName || '등록된 차량'} — 아래 작업으로 에뮬레이터 차량용 앱에 더미 요청 또는 지인 연결을 즉시 생성할 수 있습니다.`;
      driverBanner.className = 'role-notice ok compact-gap';
    } else {
      pill.textContent = '차량 미연결';
      pill.className = 'badge badge-orange';
      driverBanner.textContent = '선택한 계정에 연결된 차량이 없습니다. iPhone 앱 설정 > 차량 QR 연결에서 에뮬레이터 QR을 먼저 등록하세요.';
      driverBanner.className = 'role-notice warn compact-gap';
    }
  }

  function renderTargetBar(state) {
    const select = $('active-target-select');
    const members = state.members;
    const prev = select.value || sessionStorage.getItem(TARGET_STORAGE_KEY);
    select.textContent = '';
    for (const m of members) {
      const opt = document.createElement('option');
      opt.value = m.memberId;
      opt.textContent = `${m.dummy ? '[더미]' : '[내 폰/실회원]'} ${m.nickname} (${m.majungId})`;
      select.append(opt);
    }
    if (prev && members.some((m) => m.memberId === prev)) {
      select.value = prev;
    } else {
      const firstReal = members.find((m) => !m.dummy);
      if (firstReal) select.value = firstReal.memberId;
    }
    if (select.value) sessionStorage.setItem(TARGET_STORAGE_KEY, select.value);
    updateTargetVehicleStatus(state);

    const envBadge = $('env-badge');
    envBadge.textContent = state.deployment ? '운영 서버 (majung.cloud)' : '로컬 서버';
    envBadge.className = `badge ${state.deployment ? 'badge-red' : 'badge-blue'}`;
    $('slot-badge').textContent = `slot: ${state.slot || 'standalone'}`;

    $('kpi-real').textContent = String(state.stats.realMembers);
    $('kpi-dummy').textContent = String(state.stats.dummyMembers);
    $('kpi-pickups').textContent = String(state.stats.openPickups);
    $('kpi-requests').textContent = String(state.stats.openRequests);
  }

  function appendVehicleControls(actions, p) {
    if (p.status === 'WAITING_APPROVAL') {
      actions.append(actionBtn('게스트 탑승 승인', 'btn-success', async () => {
        await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'approve-guest' });
        showBanner('게스트 탑승을 승인했습니다.');
      }));
    }
    if (p.status === 'ACTIVE') {
      const isAuto = autoDrivePickups.has(p.pickupId);
      actions.append(actionBtn('차량 접근 (-60초)', 'btn-secondary', async () => {
        await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'approach' });
        showBanner('차량을 한 단계 접근시켰습니다.');
      }));
      actions.append(actionBtn(isAuto ? '자동 주행 중지' : '자동 주행 시작 (5초)', isAuto ? 'btn-danger' : 'btn-secondary', async () => {
        if (autoDrivePickups.has(p.pickupId)) {
          autoDrivePickups.delete(p.pickupId);
          showBanner('자동 주행을 멈췄습니다.');
        } else {
          autoDrivePickups.add(p.pickupId);
          showBanner('5초 간격 자동 주행을 시작했습니다.');
        }
      }));
      actions.append(actionBtn('도착 처리 (ARRIVED)', 'btn-primary', async () => {
        autoDrivePickups.delete(p.pickupId);
        await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'arrive' });
        showBanner('도착(ARRIVED) 처리 및 알림을 보냈습니다.');
      }));
      if (p.proposalLabel) {
        actions.append(actionBtn('장소 제안 수락', 'btn-success', async () => {
          await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'accept-proposal' });
          showBanner('제안된 장소로 확정했습니다.');
        }));
      }
      actions.append(actionBtn('준비 수신 확인', 'btn-secondary', async () => {
        await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'receive-readiness' });
        showBanner('탑승자 준비 수신확인을 반영했습니다.');
      }));
    }
    if (p.status === 'ACTIVE' || p.status === 'ARRIVED') {
      actions.append(actionBtn('탑승 완료', 'btn-success', async () => {
        autoDrivePickups.delete(p.pickupId);
        await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'complete' });
        showBanner('픽업을 완료했습니다.');
      }));
      actions.append(actionBtn('취소', 'btn-danger', async () => {
        autoDrivePickups.delete(p.pickupId);
        await api('/api/v1/ops/api/pickups/tick', 'POST', { pickupId: p.pickupId, action: 'cancel' });
        showBanner('픽업을 취소했습니다.');
      }));
    }
  }

  function appendDummyPassengerControls(actions, p) {
    if (!p.dummyPassenger || !['ACTIVE', 'ARRIVED'].includes(p.status)) return;
    actions.append(actionBtn('준비 상태: 지금 출발', 'btn-secondary', async () => {
      await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, readiness: 'PREPARING' });
      showBanner('더미 탑승자 상태를 "지금 출발해요"로 변경했습니다.');
    }));
    actions.append(actionBtn('준비 상태: 탑승 준비 완료', 'btn-success', async () => {
      await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, readiness: 'READY' });
      showBanner('더미 탑승자 상태를 "준비 완료"로 변경했습니다.');
    }));
    actions.append(actionBtn('빠른 메시지: 지금 내려가요', 'btn-secondary', async () => {
      await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, quickMessage: '지금 내려가요' });
      showBanner('더미 탑승자가 "지금 내려가요" 메시지를 보냈습니다.');
    }));
    actions.append(actionBtn('다른 장소 제안', 'btn-primary', async () => {
      const selected = parseSelectedPlace($('driver-place-select'));
      const alt = selected.placeLabel === p.placeLabel
        ? { label: '판교역 2번 출구', latitude: 37.3947, longitude: 127.1112 }
        : { label: selected.placeLabel, latitude: selected.latitude, longitude: selected.longitude };
      await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, proposePlace: alt });
      showBanner(`더미 탑승자가 "${alt.label}"로 장소 변경을 제안했습니다.`);
    }));
  }

  function buildPickupCard(p, compact, mode = 'all') {
    const card = document.createElement('div');
    card.className = compact ? 'remote-pickup-card' : 'item-row';

    const meta = document.createElement('div');
    meta.className = 'item-meta';

    const titleRow = document.createElement('div');
    titleRow.className = 'item-title-row';
    titleRow.append(makeBadge(p.status, statusBadgeClass(p.status)));
    const title = document.createElement('strong');
    title.textContent = `${p.placeLabel} · ${p.vehicleName}`;
    titleRow.append(title);
    if (!p.controllable) {
      titleRow.append(makeBadge(p.dummyPassenger ? '내 차량 (에뮬레이터 운전)' : '실차량 (보호됨)', 'badge-slate'));
    }

    const sub = document.createElement('div');
    sub.className = 'muted';
    const etaText = p.durationSeconds != null ? `${Math.max(1, Math.round(p.durationSeconds / 60))}분 (${p.durationSeconds}초)` : '-';
    const recvText = p.readinessReceivedSequence >= p.readinessSequence && p.readinessSequence > 0 ? ' (수신확인)' : '';
    sub.textContent = `탑승자: ${p.participantName || '미참여'} · 준비: ${p.readiness || '-'}${recvText} · 남은 ETA: ${etaText}`;
    meta.append(titleRow, sub);

    if (p.quickMessage || p.proposalLabel) {
      const sub2 = document.createElement('div');
      sub2.className = 'muted';
      const parts = [];
      if (p.quickMessage) parts.push(`메시지: "${p.quickMessage}"`);
      if (p.proposalLabel) parts.push(`장소변경 제안: ${p.proposalLabel}`);
      sub2.textContent = parts.join(' · ');
      meta.append(sub2);
    }

    const actions = document.createElement('div');
    actions.className = 'row-actions';
    if (mode !== 'driver-only' && p.controllable) {
      appendVehicleControls(actions, p);
    }
    if (mode !== 'vehicle-only') {
      appendDummyPassengerControls(actions, p);
    }

    card.append(meta, actions);
    return card;
  }

  function buildRequestRow(r, driverFocus = false) {
    const row = document.createElement('div');
    row.className = driverFocus ? 'remote-pickup-card' : 'item-row';

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

    if (r.controllable && !driverFocus) {
      if (r.status === 'PENDING') {
        actions.append(actionBtn('수락', 'btn-primary', async () => {
          await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'accept' });
          showBanner('요청을 수락했습니다.');
        }));
        actions.append(actionBtn('거절', 'btn-danger', async () => {
          await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'reject' });
          showBanner('요청을 거절했습니다.');
        }));
      }
      if (r.status === 'QUEUED') {
        actions.append(actionBtn('운행 시작', 'btn-success', async () => {
          await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'start' });
          showBanner('운행을 시작했습니다.');
        }));
      }
      if (r.status === 'ACTIVE') {
        actions.append(actionBtn('도착', 'btn-primary', async () => {
          await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'arrive' });
          showBanner('도착 상태로 전환했습니다.');
        }));
        actions.append(actionBtn('완료', 'btn-success', async () => {
          await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'complete' });
          showBanner('완료 처리했습니다.');
        }));
      }
      if (r.driverInvitation && ['INVITED', 'QUEUED'].includes(r.status)) {
        actions.append(actionBtn('초대 취소', 'btn-danger', async () => {
          await api('/api/v1/ops/api/requests/action', 'POST', { requestId: r.requestId, action: 'cancel' });
          showBanner('초대를 취소했습니다.');
        }));
      }
    }

    if (r.dummyPassenger && r.status === 'INVITED') {
      actions.append(actionBtn('초대 수락 (더미)', 'btn-success', async () => {
        await api('/api/v1/ops/api/requests/passenger-action', 'POST', { requestId: r.requestId, action: 'accept-invite' });
        showBanner('더미 탑승자가 초대를 수락해 차량 대기열에 들어갔습니다.');
      }));
      actions.append(actionBtn('초대 거절 (더미)', 'btn-danger', async () => {
        await api('/api/v1/ops/api/requests/passenger-action', 'POST', { requestId: r.requestId, action: 'reject-invite' });
        showBanner('더미 탑승자가 초대를 거절했습니다.');
      }));
    }

    if (r.dummyPassenger && ['PENDING', 'QUEUED'].includes(r.status)) {
      if (!r.controllable) {
        actions.append(makeBadge('에뮬레이터에서 수락·출발 가능', 'badge-blue'));
      }
      actions.append(actionBtn('요청 취소 (더미)', 'btn-danger', async () => {
        await api('/api/v1/ops/api/requests/passenger-action', 'POST', { requestId: r.requestId, action: 'cancel' });
        showBanner('더미 탑승자가 보낸 요청을 취소했습니다.');
      }));
    }

    if (!r.controllable && !r.dummyPassenger) {
      actions.append(makeBadge('실회원 보호', 'badge-slate'));
    }

    row.append(meta, actions);
    return row;
  }

  function renderPickups(state) {
    const pickups = state.pickups;
    for (const activeId of [...autoDrivePickups]) {
      if (!pickups.some((p) => p.pickupId === activeId && p.status === 'ACTIVE')) {
        autoDrivePickups.delete(activeId);
      }
    }

    const liveOnly = pickups.filter((p) => !['COMPLETED', 'CANCELLED', 'EXPIRED'].includes(p.status));

    // Tab 1 (Passenger role): dummy vehicles driving to my phone
    const passengerPickupsEl = $('passenger-mode-pickups');
    passengerPickupsEl.textContent = '';
    const dummyVehicleLive = liveOnly.filter((p) => p.controllable);
    if (!dummyVehicleLive.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '진행 중인 더미 차량 픽업이 없습니다. 위에서 "실시간 픽업 바로 생성" 또는 "대기 요청 즉시 수락 · 출발"을 실행하세요.';
      passengerPickupsEl.append(p);
    } else {
      for (const p of dummyVehicleLive) {
        passengerPickupsEl.append(buildPickupCard(p, true, 'vehicle-only'));
      }
    }

    // Tab 2 (Driver/Emulator role): pickups with a dummy passenger
    const driverPickupsEl = $('driver-mode-pickups');
    driverPickupsEl.textContent = '';
    const dummyPassengerLive = liveOnly.filter((p) => p.dummyPassenger);
    if (!dummyPassengerLive.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '내 차량에서 진행 중인 더미 탑승자 픽업이 아직 없습니다. 에뮬레이터에서 요청을 수락·출발시키면 여기에 나타납니다.';
      driverPickupsEl.append(p);
    } else {
      for (const p of dummyPassengerLive) {
        driverPickupsEl.append(buildPickupCard(p, true, 'driver-only'));
      }
    }

    // Tab 3 (All pickups)
    const list = $('pickups-list');
    list.textContent = '';
    if (!pickups.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '표시할 픽업 내역이 없습니다.';
      list.append(p);
    } else {
      for (const p of pickups) {
        list.append(buildPickupCard(p, false, 'all'));
      }
    }
  }

  function renderRequests(state) {
    const requests = state.requests;
    const target = selectedMember(state);

    // Tab 2 (Driver/Emulator role): open requests & invitations where driverId === target
    const driverReqsEl = $('driver-mode-requests');
    driverReqsEl.textContent = '';
    const driverOpen = requests.filter((r) =>
      target && r.driverId === target.memberId && ['PENDING', 'INVITED', 'QUEUED'].includes(r.status)
    );
    if (!driverOpen.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '내 차량으로 들어온 대기 요청이나 발송 초대가 없습니다. 위에서 "더미 탑승자 마중 요청 전송"을 실행하세요.';
      driverReqsEl.append(p);
    } else {
      for (const r of driverOpen) {
        driverReqsEl.append(buildRequestRow(r, true));
      }
    }

    // Tab 3 (All requests)
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
      list.append(buildRequestRow(r, false));
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
      inviteEl.textContent = '유효한 게스트 초대 없음 (새 링크 발급 클릭)';
      inviteAnchor.classList.add('hidden');
    }

    const qrEl = $('vehicle-qr-url');
    const qrMeta = $('vehicle-qr-meta');
    const confirmBtn = $('confirm-qr-btn');
    if (state.vehicleQr) {
      qrEl.textContent = state.vehicleQr.link;
      const codeText = state.vehicleQr.vehicleCode ? ` (${state.vehicleQr.vehicleCode})` : '';
      qrMeta.textContent = state.vehicleQr.claimantMajungId
        ? `${state.vehicleQr.claimantMajungId} 님이 요청했습니다! 오른쪽 "차량에서 승인"을 누르세요.`
        : `${state.vehicleQr.vehicleName}${codeText} · 폰 앱에서 링크 입력 후 여기서 승인하세요.`;
      confirmBtn.disabled = !state.vehicleQr.claimantMajungId;
    } else {
      qrEl.textContent = 'QR 미발급 (QR 새로 발급 클릭)';
      qrMeta.textContent = '';
      confirmBtn.disabled = true;
    }
  }

  function renderAdvanced(members) {
    const dummies = members.filter((m) => m.dummy);
    const allDrivers = members.filter((m) => m.driver).sort((a, b) => Number(a.dummy) - Number(b.dummy));
    const realFirst = [...members].sort((a, b) => Number(a.dummy) - Number(b.dummy));

    fillSelectKeepingValue($('conn-dummy-select'), dummies, (m) => `${m.nickname} (${m.majungId})`);
    fillSelectKeepingValue($('conn-target-select'), realFirst, (m) => `${m.dummy ? '[더미]' : '[실회원]'} ${m.nickname} (${m.majungId})`);
    fillSelectKeepingValue($('inv-driver-select'), allDrivers, (m) => `${m.dummy ? '[더미]' : '[내 차량]'} ${m.nickname} · ${m.vehicleName}`);
    fillSelectKeepingValue($('inv-passenger-select'), realFirst, (m) => `${m.dummy ? '[더미]' : '[실회원]'} ${m.nickname} (${m.majungId})`);

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
      if (m.driver) titleRow.append(makeBadge('차량 연결됨', 'badge-green'));
      const strong = document.createElement('strong');
      strong.textContent = `${m.nickname} (${m.majungId})`;
      titleRow.append(strong);

      const sub = document.createElement('div');
      sub.className = 'muted';
      sub.textContent = m.vehicleName
        ? (m.vehicleCode ? `${m.vehicleName} · ${m.vehicleCode}` : m.vehicleName)
        : '차량 미연결 계정';
      meta.append(titleRow, sub);

      const actions = document.createElement('div');
      actions.className = 'row-actions';
      actions.append(actionBtn('ID 복사', 'btn-secondary', async () => copyText(m.majungId, '마중 ID')));

      row.append(meta, actions);
      list.append(row);
    }
  }

  function renderAll(state) {
    lastState = state;
    renderTargetBar(state);
    renderPickups(state);
    renderRequests(state);
    renderLinks(state);
    renderAdvanced(state.members);
  }

  async function loadState() {
    const state = await api('/api/v1/ops/api/state');
    renderAll(state);
  }

  async function runQuick(kind, place = null) {
    const targetMemberId = $('active-target-select').value;
    if (!targetMemberId) {
      showBanner('상단에서 대상 계정을 먼저 선택해주세요.', true);
      return;
    }
    try {
      const payload = place
        ? { targetMemberId, kind, ...place }
        : { targetMemberId, kind };
      const res = await api('/api/v1/ops/api/quick', 'POST', payload);
      renderAll(res.state);
      showBanner(res.message);
    } catch (e) {
      showBanner(e.message, true);
    }
  }

  async function boot() {
    populatePlaceSelect($('driver-place-select'));
    populatePlaceSelect($('inv-place-select'));
    switchTab(sessionStorage.getItem(TAB_STORAGE_KEY) || 'passenger');

    const session = await api('/api/v1/ops/api/session');
    if (session.authenticated) {
      showApp(session);
      await loadState();
    } else {
      showLogin();
    }
  }

  document.querySelectorAll('.tab-btn').forEach((btn) => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });

  $('copy-key-cmd-btn').addEventListener('click', () => copyText($('key-cmd-text').textContent, '키 확인 명령어'));

  $('active-target-select').addEventListener('change', (ev) => {
    sessionStorage.setItem(TARGET_STORAGE_KEY, ev.target.value);
    if (lastState) {
      updateTargetVehicleStatus(lastState);
      renderRequests(lastState);
      renderPickups(lastState);
    }
  });

  $('copy-target-id-btn').addEventListener('click', () => {
    const m = selectedMember(lastState);
    if (m) copyText(m.majungId, '내 마중 ID');
  });

  $('q-auto-start-btn').addEventListener('click', () => runQuick('auto-start-request'));
  $('q-live-pickup-btn').addEventListener('click', () => runQuick('start-live-pickup'));
  $('q-invite-btn').addEventListener('click', () => runQuick('send-invite'));
  $('q-friend-btn').addEventListener('click', () => runQuick('send-friend-request'));
  $('q-dummy-req-my-car-btn').addEventListener('click', () => runQuick('dummy-request-my-vehicle', parseSelectedPlace($('driver-place-select'))));
  $('q-connect-dummy-btn').addEventListener('click', () => runQuick('connect-dummy-passenger'));

  $('login-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const key = $('admin-key-input').value.trim();
    if (!key) return;
    try {
      const session = await api('/api/v1/ops/api/login', 'POST', { adminKey: key });
      $('admin-key-input').value = '';
      showApp(session);
      await loadState();
      showBanner('로그인되었습니다 (7일간 유지).');
    } catch (e) {
      showLogin(e.message);
    }
  });

  $('logout-btn').addEventListener('click', async () => {
    try { await api('/api/v1/ops/api/logout', 'POST'); } finally {
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
      showBanner('더미 데이터를 초기 상태로 되돌렸습니다.');
    } catch (e) { showBanner(e.message, true); }
  });

  $('seed-samples-btn').addEventListener('click', async () => {
    try {
      renderAll(await api('/api/v1/ops/api/dummy/seed', 'POST', { withSamplePickups: true }));
      showBanner('샘플 진행 중 픽업·요청을 추가했습니다.');
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
      const place = parseSelectedPlace($('inv-place-select'));
      await api('/api/v1/ops/api/requests/submit', 'POST', {
        passengerId: $('inv-passenger-select').value,
        driverId: $('inv-driver-select').value,
        ...place,
      });
      await loadState();
      showBanner('대리 탑승 요청을 전송했습니다.');
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

  $('request-filter').addEventListener('change', () => lastState && renderRequests(lastState));
  $('member-search-input').addEventListener('input', () => lastState && renderAdvanced(lastState.members));
  $('member-role-filter').addEventListener('change', () => lastState && renderAdvanced(lastState.members));

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
      // Ignore background poll failure cleanly.
    }
  }, 5000);

  boot().catch((e) => showBanner(e.message, true));
})();
