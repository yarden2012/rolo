class Pkgfind < Formula
  desc "Search Flatpak, Fedora RPM, Homebrew and distrobox containers at once"
  homepage "https://github.com/yarden2012/rolo/tree/main/pkgfind"
  url "https://github.com/yarden2012/rolo/archive/refs/tags/pkgfind-v0.6.1.tar.gz"
  version "0.6.1"
  sha256 "ff22c6f0ec7f09c4b4c8583de37cbfc2c08bd4970df313174adeebb0509f4cce"
  license "MIT"

  # The GUI is GTK4 + libadwaita through PyGObject. Linux systems this tool
  # targets (Fedora atomic) already ship all of that with the system Python;
  # on macOS it comes from Homebrew.
  on_macos do
    depends_on "adwaita-icon-theme"
    depends_on "gtk4"
    depends_on "libadwaita"
    depends_on "pygobject3"
  end

  def install
    cd "pkgfind" do
      libexec.install "pkgfind", "pkgfind.py", "app.py", "backends.py", "winapp.py"
    end
    chmod 0755, libexec/"pkgfind"
    chmod 0755, libexec/"pkgfind.py"
    bin.install_symlink libexec/"pkgfind"
  end

  def caveats
    on_macos do
      <<~EOS
        On macOS the only searchable source is Homebrew itself, so pkgfind
        degrades to a friendlier `brew search`. Requires macOS 12.3 or newer.
      EOS
    end
  end

  test do
    assert_match "Search Flatpak", shell_output("#{bin}/pkgfind --help")
  end
end
