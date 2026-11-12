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
  const VEHICLE_STORAGE_KEY = 'majung.ops.selectedVehicleId';
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
    const targetName = ['driver', 'passenger', 'manage'].includes(name) ? name : 'driver';
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

  function selectedVehicle(state) {
    const selectedId = $('active-vehicle-select').value;
    return state?.vehicles?.find((v) => v.vehicleId === selectedId) || null;
  }

  function updateTargetVehicleStatus(state) {
    const pill = $('target-vehicle-pill');
    const m = selectedMember(state);
    if (!m) {
      pill.textContent = '실회원 없음';
      pill.className = 'badge badge-slate';
    } else if (m.driver) {
      pill.textContent = `연결 차량: ${m.vehicleName || '등록됨'}`;
      pill.className = 'badge badge-green';
    } else {
      pill.textContent = '차량 미연결';
      pill.className = 'badge badge-orange';
    }

    const vPill = $('vehicle-link-pill');
    const driverBanner = $('driver-mode-status');
    const v = selectedVehicle(state);
    if (!v) {
      vPill.textContent = '차량 없음';
      vPill.className = 'badge badge-slate';
      driverBanner.textContent = '등록된 차량이 없습니다. 위 버튼으로 43자리 기기 등록 코드를 발급해 차량용 앱에 등록하거나 차량 코드로 찾아보세요.';
      driverBanner.className = 'role-notice compact-gap';
      return;
    }
    if (v.linked) {
      vPill.textContent = `연결됨 · ${v.linkedMemberNickname || v.linkedMemberMajungId || '운전자'}`;
      vPill.className = 'badge badge-green';
      driverBanner.textContent = `${v.displayName} (${v.vehicleCode}) — 연결 운전자: ${v.linkedMemberNickname} (${v.linkedMemberMajungId}). 아래에서 다른 사용자 마중 요청·지인 연결·게스트 참여를 바로 테스트할 수 있습니다.`;
      driverBanner.className = 'role-notice ok compact-gap';
    } else {
      vPill.textContent = `미연결 · ${v.vehicleCode}`;
      vPill.className = 'badge badge-blue';
      driverBanner.textContent = `${v.displayName} (${v.vehicleCode}) — 운전자 계정 미연결 상태입니다. 아래에서 "다른 사용자로 마중 요청 보내기"나 "차량 QR 연결 대행", "게스트 참여 대행"을 바로 실행할 수 있습니다.`;
      driverBanner.className = 'role-notice ok compact-gap';
    }
  }

  function renderTargetBar(state) {
    const select = $('active-target-select');
    const members = state.members || [];
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

    const vSelect = $('active-vehicle-select');
    const vehicles = state.vehicles || [];
    const prevVeh = vSelect.value || sessionStorage.getItem(VEHICLE_STORAGE_KEY);
    vSelect.textContent = '';
    for (const v of vehicles) {
      const opt = document.createElement('option');
      opt.value = v.vehicleId;
      const roleTag = v.dummy ? '[더미 차량]' : '[차량 앱]';
      const linkTag = v.linked ? `연결: ${v.linkedMemberNickname || v.linkedMemberMajungId}` : '미연결';
      opt.textContent = `${roleTag} ${v.displayName} · ${v.vehicleCode} (${linkTag})`;
      vSelect.append(opt);
    }
    if (prevVeh && vehicles.some((v) => v.vehicleId === prevVeh)) {
      vSelect.value = prevVeh;
    } else if (vehicles.length > 0) {
      vSelect.value = vehicles[0].vehicleId;
    }
    if (vSelect.value) sessionStorage.setItem(VEHICLE_STORAGE_KEY, vSelect.value);

    const dummies = members.filter((m) => m.dummy);
    const passengerSelect = $('driver-passenger-select');
    const prevPassenger = passengerSelect.value;
    passengerSelect.textContent = '';
    const autoOpt = document.createElement('option');
    autoOpt.value = '';
    autoOpt.textContent = '자동 선택 (여유 더미 탑승자)';
    passengerSelect.append(autoOpt);
    for (const d of [...dummies].sort((a, b) => Number(a.driver) - Number(b.driver))) {
      const opt = document.createElement('option');
      opt.value = d.memberId;
      opt.textContent = `${d.driver ? '[운전자 더미]' : '[탑승자 더미]'} ${d.nickname} (${d.majungId})`;
      passengerSelect.append(opt);
    }
    if (prevPassenger && [...passengerSelect.options].some((o) => o.value === prevPassenger)) {
      passengerSelect.value = prevPassenger;
    }

    const claimSelect = $('claim-qr-member-select');
    fillSelectKeepingValue(claimSelect, dummies, (m) => `${m.nickname} (${m.majungId})`);

    updateTargetVehicleStatus(state);

    const envBadge = $('env-badge');
    envBadge.textContent = state.deployment ? '운영 서버 (majung.cloud)' : '로컬 서버';
    envBadge.className = `badge ${state.deployment ? 'badge-red' : 'badge-blue'}`;
    $('slot-badge').textContent = `slot: ${state.slot || 'standalone'}`;

    $('kpi-real-vehicles').textContent = String(state.stats.realVehicles ?? 0);
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
    if ((!p.dummyPassenger && !p.guestControllable) || !['ACTIVE', 'ARRIVED'].includes(p.status)) return;
    actions.append(actionBtn('준비 상태: 지금 출발', 'btn-secondary', async () => {
      await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, readiness: 'PREPARING' });
      showBanner('상대방(탑승자/게스트) 상태를 "지금 출발해요"로 변경했습니다.');
    }));
    actions.append(actionBtn('준비 상태: 탑승 준비 완료', 'btn-success', async () => {
      await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, readiness: 'READY' });
      showBanner('상대방(탑승자/게스트) 상태를 "준비 완료"로 변경했습니다.');
    }));
    actions.append(actionBtn('빠른 메시지: 지금 내려가요', 'btn-secondary', async () => {
      await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, quickMessage: '지금 내려가요' });
      showBanner('상대방이 "지금 내려가요" 메시지를 보냈습니다.');
    }));
    actions.append(actionBtn('다른 장소 제안', 'btn-primary', async () => {
      const selected = parseSelectedPlace($('driver-place-select'));
      const alt = selected.placeLabel === p.placeLabel
        ? { label: '판교역 2번 출구', latitude: 37.3947, longitude: 127.1112 }
        : { label: selected.placeLabel, latitude: selected.latitude, longitude: selected.longitude };
      await api('/api/v1/ops/api/pickups/passenger-action', 'POST', { pickupId: p.pickupId, proposePlace: alt });
      showBanner(`상대방이 "${alt.label}"로 장소 변경을 제안했습니다.`);
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
      titleRow.append(makeBadge((p.dummyPassenger || p.guestControllable) ? '내 차량 (상대방 콘솔 제어)' : '실차량 (보호됨)', 'badge-slate'));
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

    // Tab 1 (Driver/Vehicle-first role): live pickups belonging to selectedVehicle
    const driverPickupsEl = $('driver-mode-pickups');
    driverPickupsEl.textContent = '';
    const v = selectedVehicle(state);
    const vehicleLive = liveOnly.filter((p) => v && p.vehicleId === v.vehicleId);
    if (!vehicleLive.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = v
        ? `${v.displayName} (${v.vehicleCode}) 에서 진행 중인 픽업이 아직 없습니다. 위에서 요청을 보내거나 차량 앱에서 수락·출발시키면 여기에 나타납니다.`
        : '선택한 차량이 없습니다.';
      driverPickupsEl.append(p);
    } else {
      for (const p of vehicleLive) {
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
    const v = selectedVehicle(state);

    // Tab 1 (Driver/Vehicle-first role): open requests & invitations matching selectedVehicle
    const driverReqsEl = $('driver-mode-requests');
    driverReqsEl.textContent = '';
    const driverOpen = requests.filter((r) => {
      if (!v || !['PENDING', 'INVITED', 'QUEUED'].includes(r.status)) return false;
      if (r.vehicleId === v.vehicleId) return true;
      return Boolean(v.linkedMemberId && r.driverId === v.linkedMemberId && !r.vehicleId);
    });
    if (!driverOpen.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = v
        ? `${v.displayName} (${v.vehicleCode}) 으로 들어온 대기 요청이나 발송 초대가 없습니다. 위에서 "다른 사용자로 마중 요청 보내기"를 실행하세요.`
        : '선택한 차량이 없습니다.';
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
    switchTab(sessionStorage.getItem(TAB_STORAGE_KEY) || 'driver');

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
    }
  });

  $('active-vehicle-select').addEventListener('change', (ev) => {
    sessionStorage.setItem(VEHICLE_STORAGE_KEY, ev.target.value);
    if (lastState) {
      updateTargetVehicleStatus(lastState);
      renderRequests(lastState);
      renderPickups(lastState);
    }
  });

  $('copy-vehicle-code-btn').addEventListener('click', () => {
    const v = selectedVehicle(lastState);
    if (v?.vehicleCode) copyText(v.vehicleCode, '차량 코드');
  });

  $('find-vehicle-code-btn').addEventListener('click', () => {
    if (!lastState?.vehicles) return;
    const raw = ($('vehicle-code-find-input').value || '').trim().toUpperCase();
    const normalized = raw.startsWith('VEH-') ? raw : `VEH-${raw}`;
    const found = lastState.vehicles.find((v) => (v.vehicleCode || '').toUpperCase() === normalized);
    if (!found) {
      showBanner(`차량 코드 "${normalized}"와 일치하는 차량을 찾을 수 없습니다.`, true);
      return;
    }
    $('active-vehicle-select').value = found.vehicleId;
    sessionStorage.setItem(VEHICLE_STORAGE_KEY, found.vehicleId);
    updateTargetVehicleStatus(lastState);
    renderRequests(lastState);
    renderPickups(lastState);
    showBanner(`${found.displayName} (${found.vehicleCode}) 차량을 선택했습니다.`);
  });

  $('driver-issue-reg-code-btn').addEventListener('click', async () => {
    try {
      const issued = await api('/api/v1/ops/api/vehicle-registration-codes', 'POST', {});
      const out = $('driver-reg-code-output');
      out.textContent = `기기 등록 코드: ${issued.code} (만료: ${issued.expiresAt})`;
      out.classList.remove('hidden');
      await copyText(issued.code, '차량 기기 등록 코드');
    } catch (e) { showBanner(e.message, true); }
  });

  $('copy-target-id-btn').addEventListener('click', () => {
    const m = selectedMember(lastState);
    if (m) copyText(m.majungId, '내 마중 ID');
  });

  $('q-auto-start-btn').addEventListener('click', () => runQuick('auto-start-request'));
  $('q-live-pickup-btn').addEventListener('click', () => runQuick('start-live-pickup'));
  $('q-invite-btn').addEventListener('click', () => runQuick('send-invite'));
  $('q-friend-btn').addEventListener('click', () => runQuick('send-friend-request'));

  $('q-dummy-req-my-car-btn').addEventListener('click', async () => {
    const v = selectedVehicle(lastState);
    if (!v) {
      showBanner('먼저 상단에서 대상 차량을 선택해주세요.', true);
      return;
    }
    const passengerId = $('driver-passenger-select').value || null;
    const place = parseSelectedPlace($('driver-place-select'));
    try {
      const res = await api('/api/v1/ops/api/vehicles/request', 'POST', {
        vehicleId: v.vehicleId,
        passengerId,
        ...place,
      });
      renderAll(res.state);
      showBanner(res.message);
    } catch (e) { showBanner(e.message, true); }
  });

  $('q-connect-dummy-btn').addEventListener('click', async () => {
    const v = selectedVehicle(lastState);
    if (!v) {
      showBanner('먼저 상단에서 대상 차량을 선택해주세요.', true);
      return;
    }
    const dummyId = $('driver-passenger-select').value || null;
    try {
      const res = await api('/api/v1/ops/api/vehicles/connect-dummy', 'POST', {
        vehicleId: v.vehicleId,
        dummyId,
      });
      renderAll(res.state);
      showBanner(res.message);
    } catch (e) { showBanner(e.message, true); }
  });

  $('claim-qr-btn').addEventListener('click', async () => {
    const link = $('claim-qr-link-input').value.trim();
    if (!link) {
      showBanner('차량 앱에 표시된 QR 링크(#vehicle/...)를 붙여넣어 주세요.', true);
      return;
    }
    try {
      const res = await api('/api/v1/ops/api/vehicle-qr/claim', 'POST', {
        link,
        memberId: $('claim-qr-member-select').value || null,
      });
      $('claim-qr-link-input').value = '';
      renderAll(res.state);
      showBanner(res.message);
    } catch (e) { showBanner(e.message, true); }
  });

  $('join-guest-btn').addEventListener('click', async () => {
    const link = $('join-guest-link-input').value.trim();
    if (!link) {
      showBanner('차량 앱에서 만든 게스트 초대 링크(#join/...)를 붙여넣어 주세요.', true);
      return;
    }
    try {
      const res = await api('/api/v1/ops/api/guest-join', 'POST', {
        link,
        displayName: $('join-guest-name-input').value.trim() || null,
      });
      $('join-guest-link-input').value = '';
      renderAll(res.state);
      showBanner(res.message);
    } catch (e) { showBanner(e.message, true); }
  });

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
