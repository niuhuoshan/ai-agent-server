(function (global) {
  'use strict';

  var PROTOCOL = 'agent-embed';
  var VERSION = '1.0';
  var ID = /^[A-Za-z0-9_-]{8,100}$/;
  var TYPES = Object.freeze({
    INIT_CONFIG: 1, OPEN_SAVED_REPORT: 1, SYNC_STATE: 1, UPDATE_CONTEXT: 1,
    SET_THEME: 1, STOP_GENERATION: 1, CLEAR_SESSION: 1, RESET_SESSION: 1,
    SEND_COMMAND: 1, SEND_MESSAGE: 1, STOP: 1, RESUME: 1,
    NHS_WIDGET_READY: 1, INIT_SUCCESS: 1, GENERATION_STOPPED: 1,
    CONVERSATION_CHANGED: 1, OPEN_DATA_PORTAL_FULL: 1, USER_FEEDBACK: 1,
    CONNECTION_STATUS: 1, READY: 1, INITIALIZED: 1, STATE: 1,
    MESSAGE_START: 1, MESSAGE_EVENT: 1, MESSAGE_COMPLETE: 1, ERROR: 1, RESIZE: 1
  });

  function randomId(prefix) {
    var value = global.crypto && global.crypto.randomUUID
      ? global.crypto.randomUUID().replace(/-/g, '')
      : String(Date.now()) + Math.random().toString(36).slice(2);
    return prefix + '_' + value;
  }

  function exactOrigin(value) {
    var url = new URL(value, global.location.href);
    if (!/^https?:$/.test(url.protocol) || url.username || url.password
      || url.pathname !== '/' || url.search || url.hash) {
      throw new Error('Embed Origin must be an exact HTTP(S) origin');
    }
    return url.origin;
  }

  function envelope(instanceId, type, correlationId, payload) {
    if (!ID.test(instanceId) || !ID.test(correlationId)) throw new Error('Invalid Embed message id');
    return {
      protocol: PROTOCOL,
      version: VERSION,
      instanceId: instanceId,
      type: type,
      correlationId: correlationId,
      payload: payload
    };
  }

  function validMessage(value, instanceId) {
    return value && typeof value === 'object'
      && value.protocol === PROTOCOL && value.version === VERSION
      && value.instanceId === instanceId && ID.test(value.instanceId)
      && typeof value.type === 'string' && TYPES[value.type] === 1
      && typeof value.correlationId === 'string' && ID.test(value.correlationId)
      && Object.prototype.hasOwnProperty.call(value, 'payload');
  }

  function Widget(options) {
    if (!options || !(options.container instanceof HTMLElement)) throw new Error('Invalid Embed container');
    if (!/^ebt_[A-Za-z0-9_-]{43}$/.test(String(options.credential || '').trim())) {
      throw new Error('Invalid Embed launch credential');
    }
    var sourceUrl = new URL(options.embedUrl, global.location.href);
    this.targetOrigin = exactOrigin(sourceUrl.origin);
    this.parentOrigin = exactOrigin(global.location.origin);
    this.instanceId = randomId('instance');
    this.credential = String(options.credential).trim();
    this.context = options.context && typeof options.context === 'object' ? options.context : {};
    this.theme = /^(light|dark|auto)$/.test(options.theme) ? options.theme : 'auto';
    this.protocolMode = options.protocolMode === 'legacy' ? 'legacy' : 'nhs-v1';
    this.minHeight = Math.max(240, Number(options.minHeight) || 420);
    this.maxHeight = Math.max(this.minHeight, Number(options.maxHeight) || 760);
    this.pending = new Map();
    this.listeners = new Map();
    this.initializing = false;
    this.initialized = false;
    this.destroyed = false;
    sourceUrl.searchParams.set('instanceId', this.instanceId);
    sourceUrl.searchParams.set('parentOrigin', this.parentOrigin);
    this.iframe = document.createElement('iframe');
    this.iframe.src = sourceUrl.toString();
    this.iframe.title = options.title || 'AI assistant';
    this.iframe.allow = 'clipboard-write';
    this.iframe.referrerPolicy = 'strict-origin';
    this.iframe.style.cssText = 'display:block;width:100%;height:' + this.minHeight
      + 'px;border:0;background:transparent';
    this.handleMessage = this.handleMessage.bind(this);
    global.addEventListener('message', this.handleMessage);
    options.container.replaceChildren(this.iframe);
  }

  Widget.prototype.on = function (type, listener) {
    if (typeof listener !== 'function') throw new Error('Embed listener must be a function');
    var group = this.listeners.get(type) || new Set();
    group.add(listener);
    this.listeners.set(type, group);
    return function () { group.delete(listener); };
  };

  Widget.prototype.emit = function (message) {
    var direct = this.listeners.get(message.type) || [];
    var all = this.listeners.get('*') || [];
    direct.forEach(function (listener) { listener(message.payload, message); });
    all.forEach(function (listener) { listener(message.payload, message); });
  };

  Widget.prototype.post = function (message) {
    if (!this.iframe.contentWindow) throw new Error('Embed iframe is not ready');
    this.iframe.contentWindow.postMessage(message, this.targetOrigin);
  };

  Widget.prototype.command = function (type, payload, allowBeforeInit) {
    var self = this;
    if (this.destroyed) return Promise.reject(new Error('Embed instance is destroyed'));
    if (!allowBeforeInit && !this.initialized) return Promise.reject(new Error('Embed is not initialized'));
    var id = randomId('command');
    return new Promise(function (resolve, reject) {
      var timeoutMs = type === 'SEND_MESSAGE' || type === 'SEND_COMMAND' || type === 'RESUME' ? 300000 : 30000;
      var timer = global.setTimeout(function () {
        self.pending.delete(id);
        reject(new Error('Embed ' + type + ' timed out'));
      }, timeoutMs);
      self.pending.set(id, { resolve: resolve, reject: reject, timer: timer });
      self.post(envelope(self.instanceId, type, id, payload || {}));
    });
  };

  Widget.prototype.updateContext = function (context) {
    return this.command('UPDATE_CONTEXT', { context: context || {} });
  };
  Widget.prototype.syncState = function (state) {
    return this.command('SYNC_STATE', { state: state || {} });
  };
  Widget.prototype.openSavedReport = function (report) {
    return this.command('OPEN_SAVED_REPORT', { report: report || {} });
  };
  Widget.prototype.resetSession = function () { return this.command('RESET_SESSION', {}); };
  Widget.prototype.clearSession = function () { return this.command('CLEAR_SESSION', {}); };
  Widget.prototype.sendCommand = function (command, payload) {
    var body = Object.assign({ command: command }, payload || {});
    return this.command('SEND_COMMAND', body);
  };
  Widget.prototype.sendMessage = function (input, attachments) {
    var body = { input: input, attachments: attachments || [] };
    return this.protocolMode === 'legacy'
      ? this.command('SEND_MESSAGE', body)
      : this.sendCommand('SEND_MESSAGE', body);
  };
  Widget.prototype.stop = function () {
    return this.command(this.protocolMode === 'legacy' ? 'STOP' : 'STOP_GENERATION', {});
  };
  Widget.prototype.stopGeneration = Widget.prototype.stop;
  Widget.prototype.resume = function () { return this.command('RESUME', {}); };
  Widget.prototype.setTheme = function (theme) { return this.command('SET_THEME', { theme: theme }); };
  Widget.prototype.sendRawCommand = function (type, payload) { return this.command(type, payload || {}); };

  Widget.prototype.handleMessage = function (event) {
    if (this.destroyed || event.origin !== this.targetOrigin
      || event.source !== this.iframe.contentWindow
      || !validMessage(event.data, this.instanceId)) return;
    var message = event.data;
    var self = this;
    if ((message.type === 'READY' || message.type === 'NHS_WIDGET_READY')
      && !this.initialized && !this.initializing) {
      this.initializing = true;
      this.command('INIT_CONFIG', {
        credential: this.credential,
        context: this.context,
        theme: this.theme,
        contract: this.protocolMode
      }, true).then(function () {
        self.initialized = true;
        self.initializing = false;
      }).catch(function () {
        self.initializing = false;
      });
    }
    if (message.type === 'RESIZE' && message.payload && Number.isFinite(Number(message.payload.height))) {
      var height = Math.max(this.minHeight, Math.min(this.maxHeight, Math.ceil(Number(message.payload.height))));
      this.iframe.style.height = height + 'px';
    }
    var pending = this.pending.get(message.correlationId);
    if (pending && /^(MESSAGE_START|MESSAGE_EVENT|CONNECTION_STATUS)$/.test(message.type)) {
      global.clearTimeout(pending.timer);
      pending.timer = global.setTimeout(function () {
        self.pending.delete(message.correlationId);
        pending.reject(new Error('Embed stream timed out'));
      }, 300000);
    }
    if (pending && /^(INITIALIZED|INIT_SUCCESS|STATE|CONVERSATION_CHANGED|MESSAGE_COMPLETE|GENERATION_STOPPED|OPEN_DATA_PORTAL_FULL|USER_FEEDBACK|ERROR)$/.test(message.type)) {
      global.clearTimeout(pending.timer);
      this.pending.delete(message.correlationId);
      if (message.type === 'ERROR') {
        pending.reject(new Error(message.payload && message.payload.message || 'Embed operation failed'));
      } else {
        pending.resolve(message.payload);
      }
    }
    this.emit(message);
  };

  Widget.prototype.destroy = function () {
    if (this.destroyed) return;
    this.destroyed = true;
    global.removeEventListener('message', this.handleMessage);
    this.pending.forEach(function (item) {
      global.clearTimeout(item.timer);
      item.reject(new Error('Embed instance is destroyed'));
    });
    this.pending.clear();
    this.listeners.clear();
    this.iframe.remove();
  };

  global.AgentEmbed = Object.freeze({
    version: VERSION,
    create: function (options) { return new Widget(options); }
  });
}(window));
