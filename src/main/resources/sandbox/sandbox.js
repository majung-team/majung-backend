(() => {
  const banner = document.getElementById('banner');
  const sessionStatus = document.getElementById('session-status');
  const accountsGrid = document.getElementById('accounts-grid');
  const passengerSelect = document.getElementById('sim-passenger');
  const driverSelect = document.getElementById('sim-driver');
  const placeSelect = document.getElementById('sim-place');
  const guestInviteEl = document.getElementById('guest-invite-url');
  const openInviteLink = document.getElementById('open-invite-link');
  const vehicleQrEl = document.getElementById('vehicle-qr-url');
  const vehicleQrMeta = document.getElementById('vehicle-qr-meta');
  const pickupsList = document.getElementById('pickups-list');
  const requestsList = document.getElementById('requests-list');
  const injectQuery = document.getElementById('inject-query');

  let currentChallengeId = null;

  function showBanner(msg, isError = false) {
    banner.textContent = msg;
    banner.classList.toggle('error', isError);
    banner.classList.remove('hidden');
  }

  async function api(path, method = 'GET', body = undefined) {
    const opts = {
      method,
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    };
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    const res = await fetch(path, opts);
    if (!res.ok) {
      let msg = `요청 실패 (${res.status})`;
      try {
        const err = await res.json();
        if (err && err.message) msg = err.message;
      } catch (_) {}
      throw new Error(msg);
    }
    if (res.status === 204) return null;
    return res.json();
  }

  function renderAccounts(state) {
    accountsGrid.textContent = '';
    const prevPas = passengerSelect.value;
    const prevDrv = driverSelect.value;
    passengerSelect.textContent = '';
    driverSelect.textContent = '';

    const currentId = state.currentMember ? state.currentMember.memberId : null;
    sessionStatus.textContent = state.currentMember
      ? `현재 로그인: ${state.currentMember.nickname} (${state.currentMember.majungId})`
      : '현재 비로그인 상태입니다. 아래 계정을 눌러 1초 만에 로그인할 수 있습니다.';

    for (const acc of state.accounts) {
      const tile = document.createElement('div');
      tile.className = 'account-tile' + (acc.memberId === currentId ? ' active' : '');

      const head = document.createElement('div');
      head.className = 'account-title';
      const nameSpan = document.createElement('span');
      nameSpan.textContent = acc.nickname;
      const pill = document.createElement('span');
      pill.className = 'role-pill' + (acc.driver ? ' driver' : '');
      pill.textContent = acc.driver ? '운전자' : '탑승자';
      head.append(nameSpan, pill);

      const meta = document.createElement('div');
      meta.className = 'muted';
      meta.textContent = acc.driver ? `${acc.majungId} · ${acc.vehicleName}` : acc.majungId;

      const actions = document.createElement('div');
      actions.className = 'row-actions';
      const loginBtn = document.createElement('button');
      loginBtn.className = 'btn btn-sm ' + (acc.memberId === currentId ? 'btn-secondary' : 'btn-primary');
      loginBtn.type = 'button';
      loginBtn.textContent = acc.memberId === currentId ? '현재 세션' : '이 계정으로 로그인';
      loginBtn.addEventListener('click', async () => {
        try {
          await api('/api/v1/dev/login', 'POST', { memberId: acc.memberId });
          showBanner(`${acc.nickname} (${acc.majungId}) 세션으로 전환했습니다.`);
          await loadState();
        } catch (e) {
          showBanner(e.message, true);
        }
      });
      actions.append(loginBtn);
      tile.append(head, meta, actions);
      accountsGrid.append(tile);

      const pOpt = document.createElement('option');
      pOpt.value = acc.memberId;
      pOpt.textContent = `${acc.nickname} (${acc.majungId})`;
      passengerSelect.append(pOpt);

      if (acc.driver) {
        const dOpt = document.createElement('option');
        dOpt.value = acc.memberId;
        dOpt.textContent = `${acc.nickname} · ${acc.vehicleName}`;
        driverSelect.append(dOpt);
      }
    }

    if (prevPas && [...passengerSelect.options].some(o => o.value === prevPas)) passengerSelect.value = prevPas;
    if (prevDrv && [...driverSelect.options].some(o => o.value === prevDrv)) driverSelect.value = prevDrv;
  }

  function actionBtn(label, cls, onClick) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `btn btn-sm ${cls}`;
    b.textContent = label;
    b.addEventListener('click', async () => {
      try {
        await onClick();
        await loadState();
      } catch (e) {
        showBanner(e.message, true);
      }
    });
    return b;
  }

  function renderPickups(pickups) {
    pickupsList.textContent = '';
    if (!pickups.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '진행 중인 픽업이 없습니다.';
      pickupsList.append(p);
      return;
    }
    for (const p of pickups) {
      const row = document.createElement('div');
      row.className = 'item-row';

      const info = document.createElement('div');
      const title = document.createElement('strong');
      title.textContent = `[${p.status}] ${p.placeLabel} · ${p.vehicleName || '차량 미지정'}`;
      const sub = document.createElement('div');
      sub.className = 'muted';
      const eta = p.durationSeconds != null ? `${Math.round(p.durationSeconds / 60)}분 (${p.durationSeconds}초)` : '-';
      const msg = p.quickMessage ? ` · 메시지: "${p.quickMessage}"` : '';
      const prop = p.proposalLabel ? ` · 제안됨: ${p.proposalLabel}` : '';
      sub.textContent = `탑승자: ${p.participantName || '미참여'} · 준비: ${p.readiness || '-'} · ETA: ${eta}${msg}${prop}`;
      info.append(title, sub);

      const btns = document.createElement('div');
      btns.className = 'row-actions';
      if (p.status === 'ACTIVE') {
        btns.append(actionBtn('차량 이동 (-60초)', 'btn-secondary', async () => {
          await api('/api/v1/dev/simulate/vehicle-tick', 'POST', { pickupId: p.pickupId, action: 'approach' });
          showBanner('차량 위치와 ETA를 목적지 방향으로 전진시켰습니다.');
        }));
        btns.append(actionBtn('도착 처리', 'btn-primary', async () => {
          await api('/api/v1/dev/simulate/vehicle-tick', 'POST', { pickupId: p.pickupId, action: 'arrive' });
          showBanner('도착(ARRIVED) 상태로 변경했습니다.');
        }));
        if (p.proposalLabel) {
          btns.append(actionBtn('장소 제안 수락', 'btn-primary', async () => {
            await api('/api/v1/dev/simulate/vehicle-tick', 'POST', { pickupId: p.pickupId, action: 'accept-proposal' });
            showBanner('제안된 장소로 목적지를 확정했습니다.');
          }));
        }
        if (p.participantName) {
          btns.append(actionBtn('준비 완료', 'btn-secondary', async () => {
            await api('/api/v1/dev/simulate/passenger-action', 'POST', { pickupId: p.pickupId, readiness: 'READY' });
            showBanner('탑승자 준비 완료(READY)를 반영했습니다.');
          }));
          btns.append(actionBtn('퀵 메시지', 'btn-secondary', async () => {
            await api('/api/v1/dev/simulate/passenger-action', 'POST', { pickupId: p.pickupId, quickMessage: '지금 내려가요' });
            showBanner('"지금 내려가요" 메시지를 전송했습니다.');
          }));
        }
      }
      if (p.status === 'ACTIVE' || p.status === 'ARRIVED') {
        btns.append(actionBtn('픽업 완료', 'btn-danger', async () => {
          await api('/api/v1/dev/simulate/vehicle-tick', 'POST', { pickupId: p.pickupId, action: 'complete' });
          showBanner('픽업을 완료(COMPLETED)했습니다.');
        }));
      }
      row.append(info, btns);
      pickupsList.append(row);
    }
  }

  function renderRequests(requests) {
    requestsList.textContent = '';
    if (!requests.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '마중 요청 내역이 없습니다.';
      requestsList.append(p);
      return;
    }
    for (const r of requests) {
      const row = document.createElement('div');
      row.className = 'item-row';

      const info = document.createElement('div');
      const title = document.createElement('strong');
      const tag = r.driverInvitation ? '운전자 초대' : '탑승자 요청';
      title.textContent = `[${r.status}] ${r.passengerNickname} → ${r.driverNickname} (${tag})`;
      const sub = document.createElement('div');
      sub.className = 'muted';
      sub.textContent = `${r.placeLabel}${r.queueOrder ? ` · 대기순번 #${r.queueOrder}` : ''}`;
      info.append(title, sub);

      const btns = document.createElement('div');
      btns.className = 'row-actions';
      if (r.status === 'PENDING') {
        btns.append(actionBtn('수락 (대기열 추가)', 'btn-primary', async () => {
          await api('/api/v1/dev/simulate/driver-action', 'POST', { requestId: r.requestId, action: 'accept' });
          showBanner('요청을 수락해 대기열에 추가했습니다.');
        }));
        btns.append(actionBtn('거절', 'btn-secondary', async () => {
          await api('/api/v1/dev/simulate/driver-action', 'POST', { requestId: r.requestId, action: 'reject' });
          showBanner('요청을 거절했습니다.');
        }));
      }
      if (r.status === 'QUEUED') {
        btns.append(actionBtn('픽업 시작', 'btn-primary', async () => {
          await api('/api/v1/dev/simulate/driver-action', 'POST', { requestId: r.requestId, action: 'start' });
          showBanner('픽업을 시작(ACTIVE)했습니다.');
        }));
      }
      if (r.status === 'PENDING' || r.status === 'INVITED' || r.status === 'QUEUED' || r.status === 'ACTIVE') {
        btns.append(actionBtn('취소', 'btn-danger', async () => {
          await api('/api/v1/dev/simulate/driver-action', 'POST', { requestId: r.requestId, action: 'cancel' });
          showBanner('요청/픽업을 취소했습니다.');
        }));
      }
      row.append(info, btns);
      requestsList.append(row);
    }
  }

  function renderLinks(state) {
    if (state.guestInvite) {
      const joinPath = `/#join/${state.guestInvite.pickupId}/${state.guestInvite.inviteToken}`;
      guestInviteEl.textContent = `${location.origin}${joinPath} (${state.guestInvite.placeLabel})`;
      openInviteLink.href = joinPath;
    } else {
      guestInviteEl.textContent = '진행 가능한 게스트 초대가 없습니다. "새 초대 만들기"를 누르세요.';
      openInviteLink.href = '/';
    }

    if (state.vehicleQr) {
      currentChallengeId = state.vehicleQr.challengeId;
      vehicleQrEl.textContent = `${location.origin}/#vehicle/${state.vehicleQr.challengeId}/${state.vehicleQr.code}`;
      vehicleQrMeta.textContent = state.vehicleQr.claimantMajungId
        ? `${state.vehicleQr.vehicleName} · 요청자: ${state.vehicleQr.claimantMajungId} (승인 대기 중)`
        : `${state.vehicleQr.vehicleName} · 웹/iOS에서 QR 링크 입력 후 승인할 수 있습니다.`;
    } else {
      currentChallengeId = null;
      vehicleQrEl.textContent = '발급된 QR 요청이 없습니다. "QR 발급"을 누르세요.';
      vehicleQrMeta.textContent = '';
    }
  }

  async function loadState() {
    const state = await api('/api/v1/dev/state');
    renderAccounts(state);
    renderPickups(state.pickups);
    renderRequests(state.requests);
    renderLinks(state);
  }

  document.getElementById('refresh-btn').addEventListener('click', () => loadState().catch(e => showBanner(e.message, true)));

  document.getElementById('reset-btn').addEventListener('click', async () => {
    try {
      await api('/api/v1/dev/reset', 'POST', {});
      showBanner('전체 더미 데이터를 초기 상태로 되돌렸습니다.');
      await loadState();
    } catch (e) { showBanner(e.message, true); }
  });

  document.getElementById('inject-btn').addEventListener('click', async () => {
    try {
      const q = injectQuery.value.trim();
      await api('/api/v1/dev/inject', 'POST', q ? { query: q } : {});
      showBanner('대상 계정에 지인·초대·진행 중 픽업 더미를 연결했습니다.');
      await loadState();
    } catch (e) { showBanner(e.message, true); }
  });

  document.getElementById('sim-request-btn').addEventListener('click', async () => {
    try {
      const [placeLabel, lat, lng] = placeSelect.value.split('|');
      await api('/api/v1/dev/simulate/request', 'POST', {
        passengerId: passengerSelect.value,
        driverId: driverSelect.value,
        placeLabel,
        latitude: Number(lat),
        longitude: Number(lng),
      });
      showBanner('새 마중 요청을 생성했습니다.');
      await loadState();
    } catch (e) { showBanner(e.message, true); }
  });

  document.getElementById('new-invite-btn').addEventListener('click', async () => {
    try {
      await api('/api/v1/dev/simulate/guest-invite', 'POST', {});
      showBanner('새 비로그인 게스트 초대 링크를 발급했습니다.');
      await loadState();
    } catch (e) { showBanner(e.message, true); }
  });

  document.getElementById('new-qr-btn').addEventListener('click', async () => {
    try {
      await api('/api/v1/dev/simulate/vehicle-qr', 'POST', {});
      showBanner('새 차량 연결 QR 링크를 발급했습니다.');
      await loadState();
    } catch (e) { showBanner(e.message, true); }
  });

  document.getElementById('confirm-qr-btn').addEventListener('click', async () => {
    try {
      if (!currentChallengeId) throw new Error('먼저 "QR 발급"을 눌러주세요.');
      await api('/api/v1/dev/simulate/vehicle-qr/confirm', 'POST', { challengeId: currentChallengeId });
      showBanner('차량 측에서 연결을 최종 승인했습니다.');
      await loadState();
    } catch (e) { showBanner(e.message, true); }
  });

  loadState().catch(e => showBanner(e.message, true));
})();
