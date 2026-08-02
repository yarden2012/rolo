class Pkgfind < Formula
  desc "Search Flatpak, Fedora RPM, Homebrew and distrobox containers at once"
  homepage "https://github.com/yarden2012/rolo/tree/main/pkgfind"
  url "https://github.com/yarden2012/rolo/archive/889fbac2a8b1eaebf2b47c1040da6714e8721217.tar.gz"
  version "0.1.0"
  sha256 "389b1acf8da736f148913fd9ad8fafc207a43cdf652896676b5d0354e63dd784"
  license "MIT"

  def install
    cd "pkgfind" do
      libexec.install "pkgfind", "pkgfind.py", "app.py", "backends.py"
    end
    chmod 0755, libexec/"pkgfind"
    chmod 0755, libexec/"pkgfind.py"
    bin.install_symlink libexec/"pkgfind"
  end

  def caveats
    <<~EOS
      The GUI needs the system Python with PyGObject and libadwaita
      (preinstalled on Fedora GNOME systems). The terminal mode works anywhere:
        pkgfind -c <search term>
    EOS
  end

  test do
    assert_match "Search Flatpak", shell_output("#{bin}/pkgfind --help")
  end
end
