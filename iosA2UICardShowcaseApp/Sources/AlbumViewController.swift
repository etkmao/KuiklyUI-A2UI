import UIKit

/// Scene 3 — the "我的相册 · 十一出行" grid page.
///
/// Built with a UICollectionView 3-column grid. All photos are generated at
/// runtime by `PhotoFactory`, so this screen has zero external asset
/// dependencies — perfect for screen-recording demos.
final class AlbumViewController: UIViewController,
                                 UICollectionViewDataSource,
                                 UICollectionViewDelegate {

    private let photos: [AlbumPhoto]
    private var collectionView: UICollectionView!
    private let headerView = AlbumHeaderView()

    init(photos: [AlbumPhoto]) {
        self.photos = photos
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "我的相册"
        view.backgroundColor = AppTheme.background

        // Header
        headerView.translatesAutoresizingMaskIntoConstraints = false
        headerView.configure(count: photos.count)
        view.addSubview(headerView)

        // Collection
        let layout = UICollectionViewFlowLayout()
        let spacing: CGFloat = 4
        layout.minimumLineSpacing = spacing
        layout.minimumInteritemSpacing = spacing
        let w = (UIScreen.main.bounds.width - spacing * 2) / 3.0
        layout.itemSize = CGSize(width: floor(w), height: floor(w))
        layout.sectionInset = .zero

        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        collectionView.backgroundColor = AppTheme.background
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(AlbumPhotoCell.self, forCellWithReuseIdentifier: AlbumPhotoCell.reuseID)
        view.addSubview(collectionView)

        NSLayoutConstraint.activate([
            headerView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            headerView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            headerView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            headerView.heightAnchor.constraint(equalToConstant: 92),

            collectionView.topAnchor.constraint(equalTo: headerView.bottomAnchor),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Subtle staggered reveal of thumbnails — reads great on video.
        for case let cell as AlbumPhotoCell in collectionView.visibleCells {
            cell.playRevealAnimation(delay: Double(cell.tag) * 0.04)
        }
    }

    // MARK: - CollectionView DataSource

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        photos.count
    }

    func collectionView(_ collectionView: UICollectionView,
                        cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let c = collectionView.dequeueReusableCell(
            withReuseIdentifier: AlbumPhotoCell.reuseID, for: indexPath) as! AlbumPhotoCell
        c.tag = indexPath.item
        c.configure(photos[indexPath.item])
        return c
    }

    // MARK: - CollectionView Delegate

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let vc = PhotoDetailViewController(photo: photos[indexPath.item])
        navigationController?.pushViewController(vc, animated: true)
    }
}

// MARK: - Header strip on the album page

final class AlbumHeaderView: UIView {

    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let tagChip = PaddedLabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = AppTheme.background

        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.font = .systemFont(ofSize: 22, weight: .bold)
        titleLabel.textColor = AppTheme.textPrimary
        titleLabel.text = "十一出行"

        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        subtitleLabel.font = .systemFont(ofSize: 13)
        subtitleLabel.textColor = AppTheme.textSecondary

        tagChip.translatesAutoresizingMaskIntoConstraints = false
        tagChip.text = "AI 智能相册"
        tagChip.font = .systemFont(ofSize: 11, weight: .semibold)
        tagChip.textColor = AppTheme.primary
        tagChip.backgroundColor = AppTheme.primarySoft
        tagChip.layer.cornerRadius = 10
        tagChip.layer.masksToBounds = true
        tagChip.padding = UIEdgeInsets(top: 4, left: 10, bottom: 4, right: 10)

        [titleLabel, subtitleLabel, tagChip].forEach(addSubview)

        NSLayoutConstraint.activate([
            titleLabel.topAnchor.constraint(equalTo: topAnchor, constant: 18),
            titleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),

            tagChip.centerYAnchor.constraint(equalTo: titleLabel.centerYAnchor),
            tagChip.leadingAnchor.constraint(equalTo: titleLabel.trailingAnchor, constant: 10),
            tagChip.heightAnchor.constraint(equalToConstant: 22),

            subtitleLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 6),
            subtitleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            subtitleLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func configure(count: Int) {
        subtitleLabel.text = "2025 · 10 月 · 共 \(count) 张 · 已为你按时间整理"
    }
}

// MARK: - Photo cell

final class AlbumPhotoCell: UICollectionViewCell {
    static let reuseID = "AlbumPhotoCell"

    private let imageView = UIImageView()
    private let overlay = UIView()
    private let locLabel = UILabel()
    private let dateLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        contentView.addSubview(imageView)

        overlay.translatesAutoresizingMaskIntoConstraints = false
        overlay.backgroundColor = UIColor.black.withAlphaComponent(0.25)
        contentView.addSubview(overlay)

        locLabel.translatesAutoresizingMaskIntoConstraints = false
        locLabel.font = .systemFont(ofSize: 11, weight: .semibold)
        locLabel.textColor = .white

        dateLabel.translatesAutoresizingMaskIntoConstraints = false
        dateLabel.font = .systemFont(ofSize: 10)
        dateLabel.textColor = UIColor.white.withAlphaComponent(0.9)

        overlay.addSubview(locLabel)
        overlay.addSubview(dateLabel)

        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),

            overlay.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            overlay.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            overlay.heightAnchor.constraint(equalToConstant: 32),

            locLabel.leadingAnchor.constraint(equalTo: overlay.leadingAnchor, constant: 8),
            locLabel.topAnchor.constraint(equalTo: overlay.topAnchor, constant: 4),

            dateLabel.leadingAnchor.constraint(equalTo: overlay.leadingAnchor, constant: 8),
            dateLabel.bottomAnchor.constraint(equalTo: overlay.bottomAnchor, constant: -4),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func configure(_ p: AlbumPhoto) {
        imageView.image = p.image
        locLabel.text = p.location
        dateLabel.text = p.date
    }

    func playRevealAnimation(delay: TimeInterval) {
        alpha = 0
        transform = CGAffineTransform(scaleX: 0.92, y: 0.92)
        UIView.animate(withDuration: 0.35, delay: delay,
                       usingSpringWithDamping: 0.85, initialSpringVelocity: 0.4,
                       options: [.allowUserInteraction]) {
            self.alpha = 1
            self.transform = .identity
        }
    }
}

// MARK: - Photo detail (tap a thumbnail to open)

final class PhotoDetailViewController: UIViewController {
    private let photo: AlbumPhoto

    init(photo: AlbumPhoto) {
        self.photo = photo
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = photo.title
        view.backgroundColor = .black

        let imageView = UIImageView(image: photo.image)
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.contentMode = .scaleAspectFit
        view.addSubview(imageView)

        let footer = UIView()
        footer.translatesAutoresizingMaskIntoConstraints = false
        footer.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        view.addSubview(footer)

        let loc = UILabel()
        loc.translatesAutoresizingMaskIntoConstraints = false
        loc.text = "📍 \(photo.location)"
        loc.font = .systemFont(ofSize: 15, weight: .medium)
        loc.textColor = .white

        let date = UILabel()
        date.translatesAutoresizingMaskIntoConstraints = false
        date.text = "2025-\(photo.date)"
        date.font = .systemFont(ofSize: 13)
        date.textColor = .white.withAlphaComponent(0.8)

        footer.addSubview(loc)
        footer.addSubview(date)

        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            footer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            footer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            footer.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            loc.leadingAnchor.constraint(equalTo: footer.leadingAnchor, constant: 20),
            loc.topAnchor.constraint(equalTo: footer.topAnchor, constant: 16),

            date.leadingAnchor.constraint(equalTo: footer.leadingAnchor, constant: 20),
            date.topAnchor.constraint(equalTo: loc.bottomAnchor, constant: 4),
            date.bottomAnchor.constraint(equalTo: footer.safeAreaLayoutGuide.bottomAnchor, constant: -16),
        ])
    }
}
