import UIKit

/// AI Chat screen — the single entry point of this demo.
///
/// The screen hosts:
///   • A navigation title "AI Chat".
///   • A UITableView rendering ChatMessage rows.
///   • A bottom input bar (text field + send button).
///   • A horizontal strip of suggested prompts shown on top of the input bar.
///
/// All replies are mocked locally. When the user hits "send", we match the
/// text against a few trigger keywords and append:
///     1) a user bubble,
///     2) a short "typing…" row, then
///     3) the matched AI reply (text + scenario card).
final class ChatViewController: UIViewController {

    // MARK: - State

    private var messages: [ChatMessage] = []

    // MARK: - Subviews

    private let tableView = UITableView(frame: .zero, style: .grouped)
    private let inputContainer = UIView()
    private let textField = UITextField()
    private let sendButton = UIButton(type: .system)
    private let suggestionBar = SuggestionBar()
    private var inputBottomConstraint: NSLayoutConstraint!

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "AI Chat"
        view.backgroundColor = AppTheme.background
        setupTable()
        setupInputBar()
        setupSuggestions()
        observeKeyboard()
        seedWelcome()
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    // MARK: - Setup

    private func setupTable() {
        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.backgroundColor = AppTheme.background
        tableView.separatorStyle = .none
        tableView.dataSource = self
        tableView.delegate = self
        tableView.estimatedRowHeight = 80
        tableView.rowHeight = UITableView.automaticDimension
        tableView.sectionHeaderHeight = 0
        tableView.sectionFooterHeight = 0
        if #available(iOS 15.0, *) {
            tableView.sectionHeaderTopPadding = 0
        }
        tableView.contentInset = UIEdgeInsets(top: 8, left: 0, bottom: 16, right: 0)
        tableView.keyboardDismissMode = .interactive
        // Register all cell kinds.
        tableView.register(UserMessageCell.self,  forCellReuseIdentifier: UserMessageCell.reuseID)
        tableView.register(AITextMessageCell.self, forCellReuseIdentifier: AITextMessageCell.reuseID)
        tableView.register(TypingIndicatorCell.self, forCellReuseIdentifier: TypingIndicatorCell.reuseID)
        tableView.register(DietPlanCardCell.self, forCellReuseIdentifier: DietPlanCardCell.reuseID)
        tableView.register(OrderDrinkCardCell.self, forCellReuseIdentifier: OrderDrinkCardCell.reuseID)
        tableView.register(AlbumHintCardCell.self, forCellReuseIdentifier: AlbumHintCardCell.reuseID)
        view.addSubview(tableView)
    }

    private func setupInputBar() {
        inputContainer.translatesAutoresizingMaskIntoConstraints = false
        inputContainer.backgroundColor = AppTheme.background
        view.addSubview(inputContainer)

        // Rounded rectangle text field (WeChat-ish).
        let pill = UIView()
        pill.translatesAutoresizingMaskIntoConstraints = false
        pill.backgroundColor = .white
        pill.layer.cornerRadius = 8
        pill.layer.borderWidth = 1
        pill.layer.borderColor = AppTheme.cardBorder.cgColor
        inputContainer.addSubview(pill)

        textField.translatesAutoresizingMaskIntoConstraints = false
        textField.placeholder = "问点什么…"
        textField.font = .systemFont(ofSize: 16)
        textField.textColor = AppTheme.textPrimary
        textField.returnKeyType = .send
        textField.delegate = self
        pill.addSubview(textField)

        sendButton.translatesAutoresizingMaskIntoConstraints = false
        sendButton.backgroundColor = AppTheme.primary
        sendButton.layer.cornerRadius = 8
        sendButton.setTitle("发送", for: .normal)
        sendButton.setTitleColor(.white, for: .normal)
        sendButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        sendButton.addTarget(self, action: #selector(onSend), for: .touchUpInside)
        inputContainer.addSubview(sendButton)

        // Lay out: [suggestions][pill][send] all within inputContainer.
        NSLayoutConstraint.activate([
            pill.leadingAnchor.constraint(equalTo: inputContainer.leadingAnchor, constant: 12),
            pill.trailingAnchor.constraint(equalTo: sendButton.leadingAnchor, constant: -8),
            pill.heightAnchor.constraint(equalToConstant: 38),
            pill.bottomAnchor.constraint(equalTo: inputContainer.bottomAnchor, constant: -10),

            textField.leadingAnchor.constraint(equalTo: pill.leadingAnchor, constant: 12),
            textField.trailingAnchor.constraint(equalTo: pill.trailingAnchor, constant: -12),
            textField.topAnchor.constraint(equalTo: pill.topAnchor),
            textField.bottomAnchor.constraint(equalTo: pill.bottomAnchor),

            sendButton.trailingAnchor.constraint(equalTo: inputContainer.trailingAnchor, constant: -12),
            sendButton.centerYAnchor.constraint(equalTo: pill.centerYAnchor),
            sendButton.widthAnchor.constraint(equalToConstant: 60),
            sendButton.heightAnchor.constraint(equalToConstant: 38),
        ])
    }

    private func setupSuggestions() {
        suggestionBar.translatesAutoresizingMaskIntoConstraints = false
        suggestionBar.prompts = MockContent.suggestionPrompts
        suggestionBar.onTap = { [weak self] text in
            self?.textField.text = text
            self?.onSend()
        }
        view.addSubview(suggestionBar)

        inputBottomConstraint = inputContainer.bottomAnchor.constraint(
            equalTo: view.safeAreaLayoutGuide.bottomAnchor)

        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: suggestionBar.topAnchor),

            suggestionBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            suggestionBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            suggestionBar.heightAnchor.constraint(equalToConstant: 48),
            suggestionBar.bottomAnchor.constraint(equalTo: inputContainer.topAnchor),

            inputContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            inputContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            inputContainer.heightAnchor.constraint(equalToConstant: 64),
            inputBottomConstraint,
        ])
    }

    private func observeKeyboard() {
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(onKbChange(_:)),
                       name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        nc.addObserver(self, selector: #selector(onKbHide),
                       name: UIResponder.keyboardWillHideNotification, object: nil)
    }

    @objc private func onKbChange(_ n: Notification) {
        guard let f = (n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue
        else { return }
        let overlap = max(0, view.bounds.height - f.origin.y - view.safeAreaInsets.bottom)
        inputBottomConstraint.constant = -overlap
        UIView.animate(withDuration: 0.25) { self.view.layoutIfNeeded() }
    }
    @objc private func onKbHide() {
        inputBottomConstraint.constant = 0
        UIView.animate(withDuration: 0.25) { self.view.layoutIfNeeded() }
    }

    // MARK: - Welcome message

    private func seedWelcome() {
        messages.append(.aiText(
            text: "你好，我是你的 AI 助手 ✨\n可以试试下面的 3 个场景，看看 A2UI 卡片的表现力～",
            streaming: false))
        tableView.reloadData()
    }

    // MARK: - Sending

    @objc private func onSend() {
        let text = (textField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        textField.text = ""
        send(userText: text)
    }

    private func send(userText: String) {
        append(.user(text: userText))
        // Dispatch the right scripted reply based on keyword match.
        if matches(userText, any: ["减肥", "减脂", "食谱", "一周"]) {
            replyDietPlan(for: userText)
        } else if matches(userText, any: ["点", "喝", "茶", "奶茶", "无糖", "饮"]) {
            replyDrinkOrder(for: userText)
        } else if matches(userText, any: ["相册", "照片", "十一", "出行", "国庆"]) {
            replyAlbumJump(for: userText)
        } else {
            replyFallback()
        }
    }

    private func matches(_ text: String, any keywords: [String]) -> Bool {
        keywords.contains { text.contains($0) }
    }

    // MARK: - Scripted replies

    /// Scene 1: streamed intro text, then the Tab+List diet card.
    private func replyDietPlan(for userText: String) {
        showTypingThen(after: 0.4) {
            let intro = "好嘞！为你规划了 7 天减脂食谱，日均约 1400 kcal，以 高蛋白 + 低 GI 为主。\n下面的卡片可以切换「周一～周日」查看每日三餐 👇"
            self.streamAIText(intro) {
                self.append(.dietCard(MockContent.dietPlan))
            }
        }
    }

    /// Scene 2: streamed intro text, then the drink-order card.
    private func replyDrinkOrder(for userText: String) {
        showTypingThen(after: 0.4) {
            let intro = "已为你精选 5 款 0 糖 / 低卡茶饮 ☕️\n选择喜欢的款式，点「立即下单」即可去结账～"
            self.streamAIText(intro) {
                self.append(.drinkCard(MockContent.drinkMenu))
            }
        }
    }

    /// Scene 3: a small hint banner, then auto-navigate to the album page.
    private func replyAlbumJump(for userText: String) {
        showTypingThen(after: 0.35) {
            let intro = "好的，正在为你整理「十一出行」相关的照片 🏞️"
            self.streamAIText(intro) {
                self.append(.albumHint(text: "共找到 7 张照片，正在打开相册…"))
                // Auto-jump after a short, natural pause so viewers can read the banner.
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { [weak self] in
                    guard let self else { return }
                    let vc = AlbumViewController(photos: MockContent.albumPhotos)
                    self.navigationController?.pushViewController(vc, animated: true)
                }
            }
        }
    }

    private func replyFallback() {
        showTypingThen(after: 0.3) {
            self.streamAIText("可以试试点击下方三条建议 👇 快速体验 A2UI 卡片示例。")
        }
    }

    // MARK: - Mock streaming helpers

    /// Show the "typing…" row, then after `delay` seconds remove it and call `work`.
    private func showTypingThen(after delay: TimeInterval, _ work: @escaping () -> Void) {
        append(.typing)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.removeTyping()
            work()
        }
    }

    /// Append an empty AI bubble, then reveal `full` character-by-character.
    private func streamAIText(_ full: String, completion: (() -> Void)? = nil) {
        // Insert streaming row.
        messages.append(.aiText(text: "", streaming: true))
        let ip = IndexPath(row: messages.count - 1, section: 0)
        tableView.insertRows(at: [ip], with: .fade)
        scrollToBottom()

        // Reveal char by char.
        let chars = Array(full)
        let step  = 0.025
        var idx   = 0
        Timer.scheduledTimer(withTimeInterval: step, repeats: true) { [weak self] t in
            guard let self else { t.invalidate(); return }
            idx = min(idx + 1, chars.count)
            let partial = String(chars.prefix(idx))
            let streaming = idx < chars.count
            // Guard against reorder — streaming row is always the last aiText at ip.
            if ip.row < self.messages.count {
                self.messages[ip.row] = .aiText(text: partial, streaming: streaming)
                UIView.performWithoutAnimation {
                    self.tableView.reloadRows(at: [ip], with: .none)
                }
                self.scrollToBottom(animated: false)
            }
            if !streaming {
                t.invalidate()
                completion?()
            }
        }
    }

    // MARK: - Table ops

    private func append(_ m: ChatMessage) {
        messages.append(m)
        let ip = IndexPath(row: messages.count - 1, section: 0)
        tableView.insertRows(at: [ip], with: .fade)
        scrollToBottom()
    }

    private func removeTyping() {
        guard let idx = messages.firstIndex(where: {
            if case .typing = $0 { return true } else { return false }
        }) else { return }
        messages.remove(at: idx)
        tableView.deleteRows(at: [IndexPath(row: idx, section: 0)], with: .fade)
    }

    private func scrollToBottom(animated: Bool = true) {
        guard !messages.isEmpty else { return }
        let ip = IndexPath(row: messages.count - 1, section: 0)
        DispatchQueue.main.async {
            self.tableView.scrollToRow(at: ip, at: .bottom, animated: animated)
        }
    }
}

// MARK: - UITableView DataSource/Delegate

extension ChatViewController: UITableViewDataSource, UITableViewDelegate {

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        messages.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let msg = messages[indexPath.row]
        switch msg {
        case .user(let text):
            let c = tableView.dequeueReusableCell(withIdentifier: UserMessageCell.reuseID,
                                                   for: indexPath) as! UserMessageCell
            c.configure(text: text); return c

        case .aiText(let text, let streaming):
            let c = tableView.dequeueReusableCell(withIdentifier: AITextMessageCell.reuseID,
                                                   for: indexPath) as! AITextMessageCell
            c.configure(text: text, streaming: streaming); return c

        case .typing:
            let c = tableView.dequeueReusableCell(withIdentifier: TypingIndicatorCell.reuseID,
                                                   for: indexPath) as! TypingIndicatorCell
            c.start(); return c

        case .dietCard(let plan):
            let c = tableView.dequeueReusableCell(withIdentifier: DietPlanCardCell.reuseID,
                                                   for: indexPath) as! DietPlanCardCell
            c.configure(plan: plan); return c

        case .drinkCard(let menu):
            let c = tableView.dequeueReusableCell(withIdentifier: OrderDrinkCardCell.reuseID,
                                                   for: indexPath) as! OrderDrinkCardCell
            c.configure(menu: menu)
            c.onBuy = { [weak self] drink, count in
                let vc = CheckoutViewController(drink: drink, count: count)
                self?.navigationController?.pushViewController(vc, animated: true)
            }
            return c

        case .albumHint(let text):
            let c = tableView.dequeueReusableCell(withIdentifier: AlbumHintCardCell.reuseID,
                                                   for: indexPath) as! AlbumHintCardCell
            c.configure(text: text); return c
        }
    }
}

// MARK: - UITextFieldDelegate

extension ChatViewController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        onSend(); return true
    }
}
