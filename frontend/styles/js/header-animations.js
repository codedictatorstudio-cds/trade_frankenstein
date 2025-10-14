export function initializeAnimations(rootElement) {
  // Add ripple effect to status chips on hover
  const chips = rootElement.querySelectorAll('.tf-status-chip, .tf-enhanced-status-chip');

  chips.forEach(chip => {
    chip.addEventListener('mouseenter', () => {
      chip.classList.add('ripple-active');
    });
    chip.addEventListener('animationend', () => {
      chip.classList.remove('ripple-active');
    });
  });
}