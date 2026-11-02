// في الجزء الذي يتعامل مع "poll":
if (action === 'poll') {
  // أضف هذا للتحقق من حالة التوليد:
  const generating = !!find(C.stop);
  if (generating) {
    return result('streaming', {text: value, generating: true});
  }
  // ... باقي الكود الحالي
}