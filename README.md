# Ktuvit for CloudStream

תוסף שמוסיף כתוביות בעברית מ-ktuvit.me לתוך חיפוש הכתוביות של CloudStream.

## איך זה עובד

ל-CloudStream אין וו לרישום ספק כתוביות מתוך תוסף. הרשימה היא מערך בגודל קבוע

```kotlin
private static final SubtitleRepo[] subtitleProviders;
```

אז התוסף מחליף את המערך במערך ארוך יותר בעזרת Reflection, בזמן הטעינה.
הבנייה הרשמית לא מערפלת שמות, אז השדה נמצא לפי שמו.

זה לא ממשק ציבורי ועלול להישבר בעדכון. הקוד מדווח על כל כישלון במקום לבלוע אותו.

## מבנה

```
KtuvitSubtitles/src/main/kotlin/com/yonatan/ktuvit/
    KtuvitPlugin.kt   נקודת הכניסה, הזרקה ומסך ההגדרות
    KtuvitApi.kt      המימוש של SubtitleAPI - חיפוש והורדה
    KtuvitClient.kt   הלקוח של ktuvit.me
    KtuvitStore.kt    שמירת פרטי ההתחברות
    Injector.kt       ההזרקה למערך ספקי הכתוביות
```

## בנייה

הבנייה רצה ב-GitHub Actions בדחיפה לענף הראשי.
התוצרים נדחפים לענף `builds`, ומשם מתקינים דרך כתובת ה-repo בתוך האפליקציה.

## הגדרה באפליקציה

בהגדרות התוסף מזינים אימייל וסיסמה מגובבת, או לחלופין את תוכן עוגיית `Login`
ישירות. אחרי שמירה התוסף מנסה להתחבר ומציג הודעה.

## מקורות

הזרימה מול ktuvit.me מבוססת על הלקוח הלא רשמי
[Ktuvit-api](https://github.com/maormagori/Ktuvit-api)
ששימש את האדון ל-Stremio.
